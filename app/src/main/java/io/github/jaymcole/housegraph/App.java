package io.github.jaymcole.housegraph;

import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.plugin.PluginInstaller;
import io.github.jaymcole.housegraph.plugin.PluginLoader;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.logging.Logging;
import io.github.jaymcole.housegraph.storage.AppDirectories;
import io.github.jaymcole.housegraph.storage.AppPreferences;
import io.github.jaymcole.housegraph.ui.GraphCanvas;
import io.github.jaymcole.housegraph.ui.io.GraphFileIO;
import io.github.jaymcole.housegraph.ui.editor.SecretsEditor;
import io.github.jaymcole.housegraph.ui.log.LogLevelPreferences;
import io.github.jaymcole.housegraph.ui.log.LogWindow;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * JavaFX application entry point for HouseGraph.
 */
public class App extends Application {

    private static final Logger log = Log.get(App.class);

    private final AppPreferences preferences = AppPreferences.load();
    private NodeGraph graph;
    private NodeRegistry nodeRegistry;
    private PluginCatalog pluginCatalog;
    private PluginLoader pluginLoader;
    private GraphCanvas canvas;

    /** The file most recently saved to or loaded from; the target for Quick Save. Null until chosen. */
    private File currentFile;

    @Override
    public void start(Stage stage) {
        // Stand up logging first (console + file + in-memory window buffer) so everything
        // from here on is captured. Idempotent, so a second entry point can call it too.
        Logging.bootstrap(AppDirectories.get().logs());
        // Reapply any per-output levels the user chose in a previous session.
        LogLevelPreferences.restore(preferences);

        // Installed node libraries, read purely from local state — no startup path makes a network
        // call, so the app opens the same offline as on. Pruning runs before any loader exists,
        // because a loader holds an open handle on its jars and on Windows an open jar can't be
        // deleted.
        pluginCatalog = PluginCatalog.load();
        PluginInstaller.pruneSupersededVersions(pluginCatalog);
        pluginLoader = PluginLoader.from(pluginCatalog, App.class.getClassLoader());
        // Install as this thread's context loader before anything spawns a thread. start() runs on
        // the FX thread, which is NOT the thread main() ran on, so doing this in Launcher would have
        // no effect here. Virtual threads inherit the creating thread's context loader, so every
        // engine execution thread created below carries it — which is what a library's own
        // ServiceLoader or Class.forName lookups need when they run inside a node's process().
        Thread.currentThread().setContextClassLoader(pluginLoader.classLoader());

        graph = new NodeGraph();
        nodeRegistry = new NodeRegistry(pluginLoader.scanRoots());
        canvas = new GraphCanvas(graph, nodeRegistry);

        // Quick Save writes straight to the current file with no dialog. Until one has been
        // chosen (fresh session, never saved), it falls back to the Save-As flow.
        Button quickSaveButton = new Button("Quick Save");
        quickSaveButton.setOnAction(e -> {
            if (currentFile == null) {
                saveAs(stage, canvas);
                return;
            }
            saveTo(canvas, currentFile);
        });

        Button saveButton = new Button("Save As…");
        saveButton.setOnAction(e -> saveAs(stage, canvas));

        Button loadButton = new Button("Load");
        loadButton.setOnAction(e -> {
            File file = createFileChooser("Load Graph").showOpenDialog(stage);
            if (file == null) {
                return;
            }
            try {
                GraphFileIO.load(canvas, file);
                rememberLastFile(file);
            } catch (IOException | RuntimeException ex) {
                new Alert(Alert.AlertType.ERROR, "Failed to load graph: " + ex.getMessage()).showAndWait();
            }
        });

        Button secretsButton = new Button("Secrets…");
        secretsButton.setOnAction(e -> SecretsEditor.show(stage));

        // Opens the standalone log window. It lives in its own top-level stage (not owned by
        // this one) so it survives independently and can be closed and reopened without
        // losing history — the buffer keeps capturing while it's shut.
        Button logsButton = new Button("Logs…");
        logsButton.setOnAction(e -> LogWindow.show(preferences));

        ToolBar toolBar = new ToolBar(quickSaveButton, saveButton, loadButton, secretsButton, logsButton);

        BorderPane root = new BorderPane();
        root.setTop(toolBar);
        root.setCenter(canvas);

        stage.setTitle("HouseGraph");
        stage.setScene(new Scene(root, 1100, 750));
        stage.show();

        loadLastFileInto(canvas);
    }

    @Override
    public void stop() {
        // App is closing: dispose the graph so any long-lived node resources (timers,
        // connections) are shut down cleanly rather than leaked.
        if (graph != null) {
            graph.dispose();
        }
        // Release the handles held on installed node-library jars, so the next run can prune or
        // replace them (on Windows an open jar can be neither deleted nor overwritten).
        if (pluginLoader != null) {
            pluginLoader.close();
        }
        // Flush and close the log file so the last lines reach disk.
        Logging.shutdown();
    }

    /**
     * Rebuilds everything that depends on the set of installed node libraries, after one is
     * installed, removed, enabled or disabled. The old loader is closed first, because it holds an
     * open handle on each jar.
     * <p>
     * This is safe while no node from an affected library is on the canvas. Where one is, the
     * caller must ask for a restart instead: live nodes stay bound to the old loader's {@code Class}
     * objects, so the same type would exist twice and {@code duplicate()} would clone the old one.
     */
    public void reloadNodeLibraries() {
        if (pluginLoader != null) {
            pluginLoader.close();
        }
        pluginLoader = PluginLoader.from(pluginCatalog, App.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(pluginLoader.classLoader());
        nodeRegistry.setRoots(pluginLoader.scanRoots());
        canvas.reloadNodeTypes();
    }

    /** Prompts for a destination file, then saves the graph there. */
    private void saveAs(Stage stage, GraphCanvas canvas) {
        File file = createFileChooser("Save Graph").showSaveDialog(stage);
        if (file == null) {
            return;
        }
        saveTo(canvas, file);
    }

    /** Saves the graph to {@code file} and records it as the current/last file. */
    private void saveTo(GraphCanvas canvas, File file) {
        try {
            GraphFileIO.save(canvas, file);
            rememberLastFile(file);
        } catch (IOException ex) {
            new Alert(Alert.AlertType.ERROR, "Failed to save graph: " + ex.getMessage()).showAndWait();
        }
    }

    /** Records the just-saved/opened file as the current file and the one to reopen on the next launch. */
    private void rememberLastFile(File file) {
        currentFile = file;
        preferences.put(AppPreferences.LAST_FILE, file.getAbsolutePath());
        preferences.save();
    }

    /** Reopens the last file from a previous session, if it's recorded and still there. */
    private void loadLastFileInto(GraphCanvas canvas) {
        preferences.get(AppPreferences.LAST_FILE).ifPresent(path -> {
            File file = new File(path);
            if (!file.isFile()) {
                return;
            }
            try {
                GraphFileIO.load(canvas, file);
                currentFile = file;
            } catch (IOException | RuntimeException ex) {
                log.error("Could not reopen last file " + file, ex);
            }
        });
    }

    private static FileChooser createFileChooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("HouseGraph files", "*.json"));

        File savesDirectory = AppDirectories.get().saves().toFile();
        if (savesDirectory.isDirectory()) {
            chooser.setInitialDirectory(savesDirectory);
        }
        return chooser;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
