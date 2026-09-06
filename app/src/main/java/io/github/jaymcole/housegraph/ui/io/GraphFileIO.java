package io.github.jaymcole.housegraph.ui.io;

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
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(root.toString(2));
        }
    }

    public static void load(GraphCanvas canvas, File file) throws IOException {
        JSONObject root = io.github.jaymcole.housegraph.saveformat.GraphFileIO.readRoot(file);
        canvas.loadSnapshot(io.github.jaymcole.housegraph.saveformat.GraphFileIO.fromRoot(root, canvas.getNodeRegistry()));
        CameraState camera = io.github.jaymcole.housegraph.saveformat.GraphFileIO.cameraFromJson(root);
        canvas.setCameraState(camera);
    }
}
