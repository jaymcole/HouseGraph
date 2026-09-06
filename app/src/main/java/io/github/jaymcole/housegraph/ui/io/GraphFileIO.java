package io.github.jaymcole.housegraph.ui.io;

import io.github.jaymcole.housegraph.modules.ModuleFile;
import io.github.jaymcole.housegraph.saveformat.CameraState;
import io.github.jaymcole.housegraph.ui.GraphCanvas;

import io.github.jaymcole.housegraph.plugin.PluginDirectory;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Saves/loads a {@link GraphCanvas}'s entire contents to/from a JSON file. The three methods here
 * are thin wrappers over {@link io.github.jaymcole.housegraph.saveformat.GraphFileIO}, which owns
 * the actual JSON conversion and everything about the format: they pull
 * {@code canvas.snapshotAll()}/{@code canvas.getCameraState()} and call
 * {@code canvas.loadSnapshot(...)}/{@code canvas.setCameraState(...)} against what that class
 * parses and builds. See {@code docs/engine/save-format.md} for the format itself.
 *
 * <p>One thing is handled here and nowhere else: a graph that is itself a <b>module</b> carries a
 * stable id in its root, and that id belongs to the <em>file</em> rather than to the snapshot a
 * canvas produces. {@link #save(GraphCanvas, File, PluginDirectory)} reads the file it is about to
 * overwrite and carries the id across, because this is the only layer that knows which file that
 * is.
 */
public final class GraphFileIO {

    private GraphFileIO() {
    }

    /**
     * Saves the canvas without any library metadata, so every {@code plugins} row is a bare id.
     *
     * <p>Kept for a caller that genuinely has no catalog to hand; prefer
     * {@link #save(GraphCanvas, File, PluginDirectory)}, because a row without a repository URL
     * can't be turned into an install offer when the graph is opened somewhere else.
     */
    public static void save(GraphCanvas canvas, File file) throws IOException {
        save(canvas, file, PluginDirectory.EMPTY);
    }

    /**
     * Saves the canvas, recording each node library this graph depends on along with where it can be
     * installed from.
     *
     * @param plugins consulted for the name/version/repository of each library in use; pass the
     *                app's {@code PluginCatalog}
     */
    public static void save(GraphCanvas canvas, File file, PluginDirectory plugins) throws IOException {
        JSONObject root = io.github.jaymcole.housegraph.saveformat.GraphFileIO.toJson(
                canvas.snapshotAll(), canvas.getNodeRegistry(), plugins, canvas.getCameraState());
        ModuleFile.carryIdentity(existingRoot(file), root);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(root.toString(2));
        }
    }

    /**
     * The root currently in {@code file}, or null when there is none to read.
     *
     * <p>Read so a graph that <em>is</em> a module keeps its identity across an ordinary Save. That
     * identity belongs to the file, not to the snapshot the canvas hands over — {@code toJson}
     * builds a fresh root and would drop it, stranding every graph referencing this one. This is the
     * only place that knows which file is being overwritten, so this is where the carry-over goes;
     * a Save As to a new path correctly writes no identity, because a copy of a module is a new graph
     * until it is published.
     *
     * <p>A file that will not parse is treated as absent rather than failing the save: whatever is
     * there is about to be replaced, and refusing to write over a corrupt file would lose the user's
     * work to protect an id.
     */
    private static JSONObject existingRoot(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            return io.github.jaymcole.housegraph.saveformat.GraphFileIO.readRoot(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static void load(GraphCanvas canvas, File file) throws IOException {
        JSONObject root = io.github.jaymcole.housegraph.saveformat.GraphFileIO.readRoot(file);
        canvas.loadSnapshot(io.github.jaymcole.housegraph.saveformat.GraphFileIO.fromRoot(root, canvas.getNodeRegistry()));
        CameraState camera = io.github.jaymcole.housegraph.saveformat.GraphFileIO.cameraFromJson(root);
        canvas.setCameraState(camera);
    }
}
