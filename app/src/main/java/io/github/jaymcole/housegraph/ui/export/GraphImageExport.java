package io.github.jaymcole.housegraph.ui.export;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.ui.GraphCanvas;

import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritablePixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Writes a PNG per connected component of the canvas — a picture of each distinct graph in the file.
 *
 * <h2>Why it renders in tiles</h2>
 * A single whole-canvas {@code snapshot} does not scale. Above roughly 8192px square the Prism
 * back-end gives up <em>inside</em> the render, surfacing as
 * {@code NullPointerException: Cannot invoke "com.sun.prism.Image.getWidth()"} rather than anything
 * a caller could catch and interpret, and the exact ceiling is the graphics pipeline's maximum
 * texture size, so it varies by machine. A tidily laid-out graph crosses it somewhere around 350
 * nodes, and a sparsely laid-out one very much sooner, because empty canvas between two clusters
 * costs full pixels.
 * <p>
 * So the render is always tiled, never "try it whole and fall back": each tile is a separate
 * {@code snapshot} of a {@link #TILE_SIZE}-square window, and the tiles are copied into one
 * {@link BufferedImage}. {@link SnapshotParameters#setViewport} takes its rectangle in
 * <em>post-transform</em> pixels, which is what makes the windows line up exactly — tile seams fall
 * mid-curve with no visible join.
 *
 * <h2>Scale</h2>
 * Rendering is 1:1 with the canvas ({@link #EXPORT_SCALE}), so text in the image is exactly as crisp
 * as text on screen and the pixel cost is the graph's own extent. Supersampling would multiply the
 * buffer by the square of the factor, which is what makes a big graph's memory alarming; the plain
 * size is not — a 500-node graph is around 150MB of ARGB, a 1000-node one around 290MB.
 * <p>
 * {@link #MAX_PIXELS} is a backstop for a layout that is pathological rather than merely large — a
 * single node dragged tens of thousands of pixels from the rest of its component, which costs the
 * whole empty rectangle between them. Past it the scale is reduced to fit and the reduction logged,
 * because a smaller picture of the whole graph beats an {@link OutOfMemoryError}.
 *
 * <h2>What is in the picture</h2>
 * {@link GraphCanvas#withComponentIsolated} does the isolating: only this component's nodes and
 * edges are visible, the selection is cleared so no amber border leaks in, and pan/zoom is reset so
 * the render is 1:1 regardless of where the user had scrolled to. Hiding is what makes the crop
 * correct — two disjoint components may overlap on the canvas, so cropping to a bounding box alone
 * would pull a foreign node into the picture.
 */
public final class GraphImageExport {

    private static final Logger log = Log.get(GraphImageExport.class);

    /**
     * The square window each {@code snapshot} call renders.
     *
     * <p>Well under every plausible maximum texture size, and small enough that the reusable
     * scratch buffers it implies (a {@link WritableImage} plus an {@code int[]}, about 16MB each at
     * this size) stay incidental next to the output image. Larger tiles would mean fewer
     * {@code snapshot} calls, but each call costs about 20ms regardless, so there is nothing to win.
     */
    static final int TILE_SIZE = 2048;

    /** Canvas pixels of background left around the content, so nothing sits flush against the edge. */
    static final int MARGIN = 40;

    /** 1:1 with the canvas. See the class Javadoc. */
    static final double EXPORT_SCALE = 1.0;

    /**
     * The output-size backstop, about 268MB as ARGB. Not a limit anyone should reach by having a
     * large graph — only by having a very empty one.
     */
    static final long MAX_PIXELS = 8192L * 8192L;

    /** Matches the canvas's own background, so the image looks like what is on screen. */
    private static final Color BACKGROUND = Color.web("#1e1e1e");

    private GraphImageExport() {
    }

    /**
     * Renders every connected component of {@code canvas} to its own PNG in {@code directory}.
     *
     * <p>Files are named after {@code baseName}: a canvas holding a single graph produces
     * {@code <baseName>.png}, and one holding several produces {@code <baseName>-1.png} upward,
     * numbered left-to-right then top-to-bottom by where each component sits on the canvas. Position
     * rather than internal node order, so the numbering matches how someone reading the canvas would
     * count them, and stays stable across a save/load that reorders nodes.
     *
     * <p>Must be called on the FX Application Thread: it renders live views.
     *
     * @return the files written, in the order they were numbered; empty for an empty canvas
     * @throws IOException if a file cannot be written
     */
    public static List<File> exportComponents(GraphCanvas canvas, File directory, String baseName) throws IOException {
        List<Set<BaseNode>> components = GraphComponents.connectedComponents(canvas.getGraph());
        if (components.isEmpty()) {
            log.info("Nothing to export: the canvas is empty");
            return List.of();
        }

        // Measure first, render second. The bounds of an isolated component are only knowable while
        // it *is* isolated, and they're what the numbering sorts on, so every component is measured
        // before any file is named. Isolating is a handful of setVisible calls, so the extra pass is
        // not worth avoiding.
        List<Measured> measured = new ArrayList<>();
        for (Set<BaseNode> component : components) {
            Bounds bounds = canvas.withComponentIsolated(component, Group::getLayoutBounds);
            if (bounds.isEmpty()) {
                // No visible view for any of its nodes. Shouldn't happen for a component that came
                // out of the live graph, but an empty bounds would produce a zero-pixel image and a
                // baffling file, so drop it rather than write that.
                log.warn("Skipping a {}-node component with no visible bounds", component.size());
                continue;
            }
            measured.add(new Measured(component, bounds));
        }
        measured.sort(Comparator.comparingDouble((Measured m) -> m.bounds.getMinY())
                .thenComparingDouble(m -> m.bounds.getMinX()));

        List<File> written = new ArrayList<>();
        for (int i = 0; i < measured.size(); i++) {
            File file = new File(directory, fileName(baseName, i, measured.size()));
            Measured entry = measured.get(i);
            try {
                canvas.withComponentIsolated(entry.component, content -> {
                    write(content, file);
                    return null;
                });
            } catch (UncheckedIOException ex) {
                // Unwrapped because withComponentIsolated takes a plain Function, which cannot
                // declare IOException; the caller still gets the checked exception it expects.
                throw ex.getCause();
            }
            log.info("Exported {} nodes to {}", entry.component.size(), file.getAbsolutePath());
            written.add(file);
        }
        return written;
    }

    /** A component and the canvas rectangle it occupies, measured while it was isolated. */
    private record Measured(Set<BaseNode> component, Bounds bounds) {
    }

    /** {@code base.png} when it's the only one, {@code base-1.png} upward when it isn't. */
    static String fileName(String baseName, int index, int total) {
        return total == 1 ? baseName + ".png" : baseName + "-" + (index + 1) + ".png";
    }

    /**
     * Tiles {@code content} — already isolated to one component — into a single image and writes it.
     *
     * <p>Every tile renders a full {@link #TILE_SIZE} square even at the right and bottom edges,
     * where part of it falls outside the content: the overhang comes back as background and simply
     * isn't copied. Keeping the viewport and the scratch image a constant size is what lets both be
     * allocated once and reused for every tile, instead of once per tile.
     */
    private static void write(Group content, File file) {
        Bounds bounds = content.getLayoutBounds();
        double scale = fitScale(bounds);

        double originX = (bounds.getMinX() - MARGIN) * scale;
        double originY = (bounds.getMinY() - MARGIN) * scale;
        int totalWidth = (int) Math.ceil((bounds.getWidth() + 2 * MARGIN) * scale);
        int totalHeight = (int) Math.ceil((bounds.getHeight() + 2 * MARGIN) * scale);

        SnapshotParameters parameters = new SnapshotParameters();
        // Without this the snapshot's empty areas come out opaque WHITE, not transparent, which
        // against a dark canvas would frame every graph in a white border.
        parameters.setFill(BACKGROUND);
        parameters.setTransform(new Scale(scale, scale));

        BufferedImage out = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
        WritableImage tile = new WritableImage(TILE_SIZE, TILE_SIZE);
        int[] buffer = new int[TILE_SIZE * TILE_SIZE];
        WritablePixelFormat<java.nio.IntBuffer> format = PixelFormat.getIntArgbInstance();

        for (int top = 0; top < totalHeight; top += TILE_SIZE) {
            for (int left = 0; left < totalWidth; left += TILE_SIZE) {
                parameters.setViewport(new Rectangle2D(originX + left, originY + top, TILE_SIZE, TILE_SIZE));
                content.snapshot(parameters, tile);

                int width = Math.min(TILE_SIZE, totalWidth - left);
                int height = Math.min(TILE_SIZE, totalHeight - top);
                tile.getPixelReader().getPixels(0, 0, width, height, format, buffer, 0, TILE_SIZE);
                out.setRGB(left, top, width, height, buffer, 0, TILE_SIZE);
            }
        }

        try {
            if (!ImageIO.write(out, "png", file)) {
                throw new IOException("No PNG writer available");
            }
        } catch (IOException ex) {
            // Wrapped so it can cross the Function boundary in exportComponents, which unwraps it.
            throw new UncheckedIOException("Failed to write " + file, ex);
        }
    }

    /**
     * {@link #EXPORT_SCALE}, reduced just enough to bring a pathologically sparse component under
     * {@link #MAX_PIXELS}.
     */
    static double fitScale(Bounds bounds) {
        double width = (bounds.getWidth() + 2 * MARGIN) * EXPORT_SCALE;
        double height = (bounds.getHeight() + 2 * MARGIN) * EXPORT_SCALE;
        double pixels = width * height;
        if (pixels <= MAX_PIXELS) {
            return EXPORT_SCALE;
        }
        // Area scales with the square of the linear factor, hence the square root.
        double reduced = EXPORT_SCALE * Math.sqrt(MAX_PIXELS / pixels);
        log.warn("Component spans {}x{}px, past the {}-pixel export budget; rendering at {} scale instead of {}",
                (int) width, (int) height, MAX_PIXELS, String.format("%.3f", reduced), EXPORT_SCALE);
        return reduced;
    }
}
