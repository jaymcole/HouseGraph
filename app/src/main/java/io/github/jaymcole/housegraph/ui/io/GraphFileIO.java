package io.github.jaymcole.housegraph.ui.io;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.modules.ModuleDirectory;
import io.github.jaymcole.housegraph.modules.ModuleFile;
import io.github.jaymcole.housegraph.saveformat.CameraState;
import io.github.jaymcole.housegraph.ui.GraphCanvas;

import io.github.jaymcole.housegraph.plugin.PluginDirectory;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Saves/loads a {@link GraphCanvas}'s entire contents to/from a JSON file. The methods here are thin
 * wrappers over {@link io.github.jaymcole.housegraph.saveformat.GraphFileIO}, which owns the actual
 * JSON conversion and everything about the format: they pull
 * {@code canvas.snapshotAll()}/{@code canvas.getCameraState()} and call
 * {@code canvas.loadSnapshot(...)}/{@code canvas.setCameraState(...)} against what that class
 * parses and builds. See {@code docs/engine/save-format.md} for the format itself.
 *
 * <p>One thing is handled here and nowhere else: a graph that is itself a <b>module</b> carries a
 * stable id in its root, and that id belongs to the <em>file</em> rather than to the snapshot a
 * canvas produces. {@link #save(GraphCanvas, File, PluginDirectory, ModuleDirectory)} reads the file
 * it is about to overwrite and carries the id across, because this is the only layer that knows
 * which file that is.
 */
public final class GraphFileIO {

    private static final Logger log = Log.get(GraphFileIO.class);

    private GraphFileIO() {
    }

    /**
     * Saves the canvas without any library or module metadata, so every {@code plugins} row is a
     * bare id and every {@code modules} row degrades to what the referencing node remembers.
     *
     * <p>Kept for a caller that genuinely has neither catalog nor library to hand; prefer
     * {@link #save(GraphCanvas, File, PluginDirectory, ModuleDirectory)}, because a row without a
     * repository URL can't be turned into an install offer when the graph is opened somewhere else.
     */
    public static void save(GraphCanvas canvas, File file) throws IOException {
        save(canvas, file, PluginDirectory.EMPTY, ModuleDirectory.EMPTY);
    }

    /**
     * Saves the canvas, recording each node library this graph depends on along with where it can be
     * installed from, and each module it references along with the libraries <em>that</em> needs.
     *
     * @param plugins consulted for the name/version/repository of each library in use; pass the
     *                app's {@code PluginCatalog}
     * @param modules consulted for the name, location and own library requirements of each
     *                referenced module; pass the app's {@code ModuleLibrary}
     */
    public static void save(GraphCanvas canvas, File file, PluginDirectory plugins, ModuleDirectory modules)
            throws IOException {
        JSONObject root = io.github.jaymcole.housegraph.saveformat.GraphFileIO.toJson(
                canvas.snapshotAll(), canvas.getNodeRegistry(), plugins, modules, canvas.getCameraState());
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
     * <h4>Neither failure is silent, and they are not the same failure</h4>
     * {@code ModuleFile.carryIdentity(null, …)} is a no-op, so treating every unreadable file as
     * absent would rewrite a module <em>without its id</em> and strand every consumer, quietly. The
     * two ways reading can fail want different answers:
     *
     * <ul>
     *   <li><b>The bytes could not be read</b> ({@link IOException} — a permission, a lock, a
     *       vanishing network share). The file is probably intact and probably has an id; overwriting
     *       it now would destroy an identity that reading again in a moment would have found. The
     *       save is <b>refused</b>, and the caller reports it the way it reports any failed save.</li>
     *   <li><b>The bytes are not a graph</b> (a {@link RuntimeException} from the parser — truncated
     *       or corrupt JSON). There is no id in there to preserve, and refusing would cost the user
     *       the work now on the canvas to protect nothing. The save <b>goes ahead</b>, logged as an
     *       error naming the file.</li>
     * </ul>
     *
     * @param file the file about to be overwritten
     * @return its parsed root, or null when there is no file there or it holds no readable graph
     * @throws IOException the file is there and could not be read, so its identity is unknown rather
     *                     than absent
     */
    static JSONObject existingRoot(File file) throws IOException {
        if (!file.isFile()) {
            return null;
        }
        try {
            return io.github.jaymcole.housegraph.saveformat.GraphFileIO.readRoot(file);
        } catch (IOException | RuntimeException e) {
            return onUnreadable(file, e);
        }
    }

    /**
     * What a failed read of the file being overwritten means for the save — the decision half of
     * {@link #existingRoot}, separated from the read so it can be exercised for both failures without
     * a filesystem that can produce them on demand.
     *
     * @param file    the file that could not be read
     * @param failure what reading it threw
     * @return null, meaning "carry no identity and save anyway", for a file that is not a graph
     * @throws IOException the bytes could not be read, so the save is refused
     */
    static JSONObject onUnreadable(File file, Exception failure) throws IOException {
        if (failure instanceof IOException io) {
            log.error("Refusing to save over " + file + ": it could not be read, so if it is a module"
                    + " this save would drop its id and strand every graph referencing it", io);
            throw new IOException("Could not read the existing " + file.getName()
                    + " to keep its module identity: " + io.getMessage(), io);
        }
        log.error("The file being overwritten, " + file + ", does not parse as a graph, so any module"
                + " id it had is unrecoverable and is not carried into this save", failure);
        return null;
    }

    public static void load(GraphCanvas canvas, File file) throws IOException {
        JSONObject root = io.github.jaymcole.housegraph.saveformat.GraphFileIO.readRoot(file);
        canvas.loadSnapshot(io.github.jaymcole.housegraph.saveformat.GraphFileIO.fromRoot(root, canvas.getNodeRegistry()));
        CameraState camera = io.github.jaymcole.housegraph.saveformat.GraphFileIO.cameraFromJson(root);
        canvas.setCameraState(camera);
    }
}
