package io.github.jaymcole.housegraph;

import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.plugin.PluginInstaller;
import io.github.jaymcole.housegraph.plugin.PluginLoader;
import io.github.jaymcole.housegraph.ui.plugin.PluginWindow;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import org.json.JSONObject;
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

    /**
     * Shown in the toolbar when the graph that was reopened at startup needs node libraries that
     * aren't installed. A notice rather than a dialog: startup reopen must never block, and this
     * runs after the window is already on screen.
     * <p>
     * Built in {@link #start} rather than at field initialisation — a JavaFX control can't be
     * constructed before the toolkit is up, and {@code AppTest} instantiates this class headlessly.
     */
    private Hyperlink missingLibrariesNotice;

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
            if (file != null) {
                openGraph(stage, file, true);
            }
        });

        Button secretsButton = new Button("Secrets…");
        secretsButton.setOnAction(e -> SecretsEditor.show(stage));

        // Opens the standalone log window. It lives in its own top-level stage (not owned by
        // this one) so it survives independently and can be closed and reopened without
        // losing history — the buffer keeps capturing while it's shut.
        Button logsButton = new Button("Logs…");
        logsButton.setOnAction(e -> LogWindow.show(preferences));

        Button dependenciesButton = new Button("Node Libraries…");
        dependenciesButton.setOnAction(e -> openPluginWindow());

        // A non-blocking notice, shown only when the last-opened graph turned out to need libraries
        // that aren't installed. Deliberately not a dialog: see openGraph.
        missingLibrariesNotice = new Hyperlink();
        missingLibrariesNotice.setVisible(false);
        missingLibrariesNotice.setManaged(false);
        missingLibrariesNotice.setStyle("-fx-text-fill: #ff6b6b;");
        missingLibrariesNotice.setOnAction(e -> openPluginWindow());

        ToolBar toolBar = new ToolBar(quickSaveButton, saveButton, loadButton, secretsButton,
                logsButton, dependenciesButton, missingLibrariesNotice);

        BorderPane root = new BorderPane();
        root.setTop(toolBar);
        root.setCenter(canvas);

        stage.setTitle("HouseGraph");
        stage.setScene(new Scene(root, 1100, 750));
        stage.show();

        // Reopen the last graph. Non-interactive on purpose — see openGraph.
        preferences.get(AppPreferences.LAST_FILE)
                .map(File::new)
                .filter(File::isFile)
                .ifPresent(file -> openGraph(stage, file, false));
    }

    private void openPluginWindow() {
        PluginWindow.show(pluginCatalog, this::tryReloadNodeLibraries, canvas::countLiveNodesFrom);
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
     * installed, removed, enabled or disabled — but only when it's safe to. The old loader is closed
     * and a new one built from the current catalog, which re-scans and re-loads the classes of
     * <b>every</b> enabled library, not just the one that changed. That's only safe while no node
     * from <b>any</b> library is on the canvas: a live node would stay bound to its old loader's
     * {@code Class} object while the registry now knows only the new one, so the same type would
     * exist twice and {@code duplicate()} would clone the wrong one.
     * <p>
     * When it isn't safe, the catalog/disk change the caller already made (a JSON write, or a jar
     * installed to a fresh version-stamped path) is left as-is and simply doesn't take effect until
     * the next restart, which calls {@link #start} and reads the catalog fresh.
     *
     * @return true if the reload actually ran; false if it was skipped because a library node is live
     */
    public boolean tryReloadNodeLibraries() {
        if (canvas.hasLiveLibraryNodes()) {
            return false;
        }
        if (pluginLoader != null) {
            pluginLoader.close();
        }
        pluginLoader = PluginLoader.from(pluginCatalog, App.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(pluginLoader.classLoader());
        nodeRegistry.setRoots(pluginLoader.scanRoots());
        canvas.reloadNodeTypes();
        return true;
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

    /**
     * The single path both the Load button and the startup reopen take.
     *
     * <p>Before building anything, the file's root {@code plugins} table is compared against what's
     * installed — one pass, no class loading. What happens when something is missing depends on who
     * asked:
     *
     * <ul>
     *   <li><b>The user chose the file</b> ({@code interactive}): a dialog listing what's missing,
     *       offering to open anyway or to install from the repository the file recorded. Never
     *       installs silently — a save file is untrusted input proposing a code download.</li>
     *   <li><b>Startup reopen</b>: never blocks, never touches the network. This runs after
     *       {@code stage.show()}, so a modal would appear over an already-rendered canvas, and
     *       someone reopening the app wants to see their graph rather than a network-dependent
     *       prompt. A toolbar notice points at the library window instead.</li>
     * </ul>
     *
     * <p>Opening with missing libraries is safe because their nodes are preserved verbatim (see
     * {@code MissingNode}). Before that fix, "open anyway" would have been a data-loss trap.
     */
    private void openGraph(Stage stage, File file, boolean interactive) {
        JSONObject root;
        try {
            root = GraphFileIO.readRoot(file);
        } catch (IOException | RuntimeException ex) {
            if (interactive) {
                new Alert(Alert.AlertType.ERROR, "Failed to load graph: " + ex.getMessage()).showAndWait();
            } else {
                log.error("Could not reopen last file " + file, ex);
            }
            return;
        }

        GraphDependencyCheck.DependencyReport report = GraphDependencyCheck.inspect(root, pluginCatalog);
        if (!report.isSatisfied()) {
            if (interactive && !confirmOpenWithMissingLibraries(stage, report)) {
                return;
            }
            showMissingLibrariesNotice(report);
        } else {
            missingLibrariesNotice.setVisible(false);
            missingLibrariesNotice.setManaged(false);
        }

        try {
            canvas.loadSnapshot(GraphFileIO.fromRoot(root, nodeRegistry));
            rememberLastFile(file);
        } catch (RuntimeException ex) {
            if (interactive) {
                new Alert(Alert.AlertType.ERROR, "Failed to load graph: " + ex.getMessage()).showAndWait();
            } else {
                log.error("Could not reopen last file " + file, ex);
            }
        }
    }

    /** @return true to go ahead and open the graph */
    private boolean confirmOpenWithMissingLibraries(Stage stage, GraphDependencyCheck.DependencyReport report) {
        StringBuilder detail = new StringBuilder();
        for (GraphDependencyCheck.RequiredPlugin required : report.blocking()) {
            detail.append("  • ").append(required.label());
            if (required.repository() != null) {
                detail.append("  —  ").append(required.repository());
            }
            detail.append('\n');
        }

        ButtonType openAnyway = new ButtonType("Open anyway", ButtonBar.ButtonData.OK_DONE);
        ButtonType install = new ButtonType("Install and open", ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

        boolean anyInstallable = report.blocking().stream().anyMatch(GraphDependencyCheck.RequiredPlugin::isInstallable);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle("Missing node libraries");
        alert.setHeaderText("This graph uses node libraries that aren't installed.");
        alert.setContentText(detail + "\nOpening anyway is safe: those nodes are kept exactly as saved "
                + "and come back if you install the library later.");
        alert.getDialogPane().setMinWidth(560);
        alert.getButtonTypes().setAll(anyInstallable
                ? List.of(openAnyway, install, cancel)
                : List.of(openAnyway, cancel));

        ButtonType choice = alert.showAndWait().orElse(cancel);
        if (choice == cancel) {
            return false;
        }
        if (choice == install) {
            // Hand off to the library window, which runs its own per-repository confirmation naming
            // exactly what is about to be downloaded and run — a save file asking to fetch and
            // execute code never gets to skip that. The graph still opens now, with placeholders;
            // reopening it once the install finishes brings the real nodes back.
            report.blocking().stream()
                    .filter(GraphDependencyCheck.RequiredPlugin::isInstallable)
                    .map(GraphDependencyCheck.RequiredPlugin::repository)
                    .forEach(repository -> PluginWindow.showAndInstall(pluginCatalog,
                            this::tryReloadNodeLibraries, canvas::countLiveNodesFrom, repository));
        }
        return true;
    }

    private void showMissingLibrariesNotice(GraphDependencyCheck.DependencyReport report) {
        int count = report.blocking().size();
        missingLibrariesNotice.setText(count + " node librar" + (count == 1 ? "y" : "ies") + " missing — fix…");
        missingLibrariesNotice.setVisible(true);
        missingLibrariesNotice.setManaged(true);
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
