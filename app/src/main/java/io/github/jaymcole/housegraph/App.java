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
import io.github.jaymcole.housegraph.saveformat.GraphFileIO;
import io.github.jaymcole.housegraph.ui.GraphCanvas;
import io.github.jaymcole.housegraph.ui.export.GraphImageExport;
import io.github.jaymcole.housegraph.ui.io.RecentGraphs;
import io.github.jaymcole.housegraph.ui.editor.SecretsEditor;
import io.github.jaymcole.housegraph.ui.log.LogLevelPreferences;
import io.github.jaymcole.housegraph.ui.log.LogWindow;
import io.github.jaymcole.housegraph.ui.menu.MainMenuBar;
import io.github.jaymcole.housegraph.ui.menu.MenuActions;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * JavaFX application entry point for HouseGraph.
 *
 * <h2>Arguments</h2>
 * Launched bare, the app behaves as it always has: it reopens whatever
 * {@link AppPreferences#LAST_FILE} holds. One named parameter exists for running it under a
 * supervisor (see {@code remote/} and {@code docs/engine/remote-runtime.md}):
 * <ul>
 *   <li>{@code --graph=<path>} — open this file instead of the last one, and <b>do not</b> record it
 *       as the last file. A daemon-opened graph must not overwrite what the person at the keyboard
 *       had open, and on a machine running several graphs at once "last" is meaningless anyway.</li>
 * </ul>
 *
 * <h2>Shutdown</h2>
 * JavaFX calls {@link #stop()} when the platform exits, but <b>not</b> when the JVM is signalled.
 * Without a hook, a {@code kill} — which is exactly how a supervisor restarts a graph — would skip
 * {@code NodeGraph.dispose()}, so no node's {@code onRemoved()} would run: connections, child
 * processes and timers would all be left to the OS, and the tail of the log would never reach disk.
 * {@link #installShutdownHook} closes that gap.
 *
 * <h2>The window's chrome</h2>
 * A menu bar over a short toolbar over the canvas. The menus are built by
 * {@link MainMenuBar}, which this class serves as {@link MenuActions}: every command that needs the
 * stage, the preferences store or the plugin catalog is a method here, and everything that acts on
 * the open graph the menu bar calls straight on {@link GraphCanvas}. The toolbar is only a shortcut
 * strip — each of its buttons has a menu item, so nothing lives there alone.
 */
public class App extends Application implements MenuActions {

    private static final Logger log = Log.get(App.class);

    /** Open this file instead of {@link AppPreferences#LAST_FILE}, without becoming the last file. */
    static final String GRAPH_PARAMETER = "graph";

    /**
     * How long the shutdown hook waits for {@link #stop()} to finish before giving up and letting the
     * JVM die anyway.
     *
     * <p>Derived from {@link NodeGraph#DEFAULT_RELEASE_TIMEOUT} rather than picked: the engine already
     * bounds teardown per node and runs those releases concurrently, so a whole graph's slow half
     * costs one release timeout no matter how many server nodes are on the canvas. This only has to
     * be that, plus room for the fast half and for closing the plugin loader and the log file. It is
     * deliberately <em>not</em> a budget shared between nodes — that was the thing that broke, since
     * any such number is one added node away from being too small.
     */
    private static final long SHUTDOWN_TIMEOUT_SECONDS =
            NodeGraph.DEFAULT_RELEASE_TIMEOUT.toSeconds() + 10;

    private final AppPreferences preferences = AppPreferences.load();

    /** Counted down at the end of {@link #stop()}, so the shutdown hook knows teardown finished. */
    private final CountDownLatch stopped = new CountDownLatch(1);
    private NodeGraph graph;
    private NodeRegistry nodeRegistry;
    private PluginCatalog pluginCatalog;
    private PluginLoader pluginLoader;
    private GraphCanvas canvas;

    /** The primary stage, kept so the {@link MenuActions} commands have a parent for their dialogs. */
    private Stage stage;

    /**
     * Shown in the toolbar when the graph that was reopened at startup needs node libraries that
     * aren't installed. A notice rather than a dialog: startup reopen must never block, and this
     * runs after the window is already on screen.
     * <p>
     * Built in {@link #start} rather than at field initialisation — a JavaFX control can't be
     * constructed before the toolkit is up, and {@code AppTest} instantiates this class headlessly.
     */
    private Hyperlink missingLibrariesNotice;

    /** The file most recently saved to or loaded from; what File ▸ Save writes to. Null until chosen. */
    private File currentFile;

    /**
     * Whether this run may write {@link AppPreferences#LAST_FILE}. False when a graph was named with
     * {@code --graph}: a supervised instance must not overwrite what the person at the keyboard had
     * open, and on a machine running several graphs at once there is no single "last" file to record.
     */
    private boolean trackLastFile = true;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
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
        // The node search box resolves a library id to its human name through the same catalog
        // the library window edits, so a renamed or reinstalled library is reflected
        // without the canvas needing its own copy of that mapping.
        canvas = new GraphCanvas(graph, nodeRegistry,
                id -> pluginCatalog.byId(id).map(PluginCatalog.Installed::name).orElse(null));

        // A non-blocking notice, shown only when the last-opened graph turned out to need libraries
        // that aren't installed. Deliberately not a dialog: see openGraph.
        missingLibrariesNotice = new Hyperlink();
        missingLibrariesNotice.setVisible(false);
        missingLibrariesNotice.setManaged(false);
        missingLibrariesNotice.setStyle("-fx-text-fill: #ff6b6b;");
        missingLibrariesNotice.setOnAction(e -> openPluginWindow());

        BorderPane root = new BorderPane();
        root.setTop(new VBox(new MainMenuBar(canvas, this), buildToolBar()));
        root.setCenter(canvas);

        updateTitle();
        stage.setScene(new Scene(root, 1100, 750));
        stage.show();

        installShutdownHook();

        // A graph named on the command line wins over the remembered one. Non-interactive either
        // way — see openGraph.
        Optional<File> requested = requestedGraph();
        trackLastFile = requested.isEmpty();
        requested.filter(file -> !file.isFile())
                .ifPresent(file -> log.error("No graph file at {}", file.getAbsolutePath()));
        requested.or(() -> preferences.get(AppPreferences.LAST_FILE).map(File::new))
                .filter(File::isFile)
                .ifPresent(file -> openGraph(file, false));
    }

    /** The {@code --graph=<path>} argument, if one was given. */
    private Optional<File> requestedGraph() {
        Map<String, String> named = getParameters() == null ? Map.of() : getParameters().getNamed();
        String path = named.get(GRAPH_PARAMETER);
        return path == null || path.isBlank() ? Optional.empty() : Optional.of(new File(path.trim()));
    }

    /**
     * Makes a signalled JVM shut down the same way a closed window does.
     *
     * <p>{@code Platform.exit()} is what triggers {@link #stop()}, and it runs teardown on the FX
     * thread — so the hook has to hand off and then <em>wait</em>, or the JVM would exit out from
     * under the very cleanup it just asked for. The latch is counted down at the end of
     * {@code stop()}; the timeout means a node that refuses to shut down delays the restart rather
     * than blocking it forever.
     */
    private void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Platform.exit();
            try {
                if (!stopped.await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    // Can't rely on the log here: its file sink may already be closing.
                    System.err.println("HouseGraph: shutdown timed out after "
                            + SHUTDOWN_TIMEOUT_SECONDS + "s; exiting anyway");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "housegraph-shutdown"));
    }

    /**
     * The strip under the menu bar: the handful of commands worth reaching without opening a menu,
     * and the missing-libraries notice pushed to the right.
     *
     * <p>Every button here also has a menu item — the toolbar is a shortcut, never the only way to
     * reach something, which is what keeps it short. The notice is the one exception, because it is
     * a status indicator rather than a command and has nowhere in the menus to live.
     */
    private ToolBar buildToolBar() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        return new ToolBar(
                button("New", this::newGraph),
                button("Open…", this::openGraph),
                button("Save", this::saveGraph),
                new Separator(),
                button("Undo", canvas::undo),
                button("Redo", canvas::redo),
                new Separator(),
                button("Fit", canvas::zoomToFit),
                spacer,
                missingLibrariesNotice);
    }

    private static Button button(String text, Runnable action) {
        Button button = new Button(text);
        button.setOnAction(event -> action.run());
        return button;
    }

    // --- MenuActions: the commands the menu bar can't carry out on its own ------------

    @Override
    public void newGraph() {
        if (!canvas.getGraph().getNodes().isEmpty()
                && !confirm("Start a new graph?",
                        "Everything on the canvas is discarded, and anything unsaved is lost.")) {
            return;
        }
        canvas.clearGraph();
        // No file behind the new graph, so Save prompts again rather than overwriting whatever was
        // open before. The remembered last file is left alone: it is what the *next launch* reopens,
        // and emptying the canvas is not a statement about that.
        currentFile = null;
        updateTitle();
        hideMissingLibrariesNotice();
    }

    @Override
    public void openGraph() {
        File file = createFileChooser("Open Graph").showOpenDialog(stage);
        if (file != null) {
            openGraph(file, true);
        }
    }

    @Override
    public List<File> recentGraphs() {
        return RecentGraphs.load(preferences);
    }

    @Override
    public void openRecentGraph(File file) {
        // The user picked this file, so it takes the same interactive path as File ▸ Open: a missing
        // node library prompts rather than quietly leaving a notice.
        openGraph(file, true);
    }

    @Override
    public void clearRecentGraphs() {
        RecentGraphs.clear(preferences);
    }

    @Override
    public void saveGraph() {
        // Writes straight to the current file with no dialog. Until one has been chosen (fresh
        // session, never saved), it falls back to the Save-As flow.
        if (currentFile == null) {
            saveAs();
            return;
        }
        saveTo(canvas, currentFile);
    }

    @Override
    public void saveGraphAs() {
        saveAs();
    }

    @Override
    public boolean hasCurrentFile() {
        return currentFile != null;
    }

    @Override
    public void exit() {
        // Not stage.close(): this is the same path the shutdown hook takes, so stop() runs and the
        // graph is disposed rather than left to the OS.
        Platform.exit();
    }

    @Override
    public void editSecrets() {
        SecretsEditor.show(stage);
    }

    @Override
    public void manageNodeLibraries() {
        openPluginWindow();
    }

    @Override
    public void showLogs() {
        // The log window lives in its own top-level stage (not owned by this one) so it survives
        // independently and can be closed and reopened without losing history — the buffer keeps
        // capturing while it's shut.
        LogWindow.show(preferences);
    }

    @Override
    public void openDataFolder() {
        getHostServices().showDocument(AppDirectories.get().root().toUri().toString());
    }

    @Override
    public void openDocumentation() {
        getHostServices().showDocument(MainMenuBar.documentationUrl());
    }

    @Override
    public void setStepDelayMillis(long millis) {
        // Slows every flow-driven run down to something the eye can follow: the canvas already
        // animates each node and edge as it fires, this just spaces the firings out. Session-only
        // and off by default — it changes timing, so it's a thing you switch on to look at a graph,
        // not a setting a graph or a deployment should carry (see NodeGraph.setStepDelayMillis).
        graph.setStepDelayMillis(millis);
    }

    /**
     * A yes/no dialog for a command that would throw work away.
     *
     * @return true to go ahead
     */
    private boolean confirm(String header, String detail) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, detail, ButtonType.CANCEL, ButtonType.OK);
        alert.initOwner(stage);
        alert.setTitle("HouseGraph");
        alert.setHeaderText(header);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void openPluginWindow() {
        PluginWindow.show(pluginCatalog, preferences, this::tryReloadNodeLibraries, canvas::countLiveNodesFrom);
    }

    @Override
    public void stop() {
        try {
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
        } finally {
            // In a finally block because a node throwing on teardown must not leave the shutdown
            // hook waiting out its whole timeout for a stop() that has already given up.
            stopped.countDown();
        }
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
    private void saveAs() {
        File file = createFileChooser("Save Graph").showSaveDialog(stage);
        if (file == null) {
            return;
        }
        saveTo(canvas, file);
    }

    /**
     * Prompts for a directory, then writes a PNG of each distinct graph on the canvas into it.
     *
     * <p>A canvas commonly holds several unrelated automations side by side, and each gets its own
     * image — see {@code GraphComponents}. Files are named after the open graph file, so exporting
     * {@code lights.json} produces {@code lights.png}, or {@code lights-1.png} upward when there is
     * more than one graph in it.
     *
     * <p>Runs on the FX thread, unlike the other long actions in this class, because it renders the
     * live node views and those may only be touched here. Writing the files afterwards could be
     * handed to a worker, but only by holding every component's finished image in memory at once —
     * which is the one cost {@code GraphImageExport} is built to avoid. A wait cursor covers the
     * pause instead.
     */
    @Override
    public void exportImages() {
        if (canvas.getGraph().getNodes().isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "There is nothing on the canvas to export.").showAndWait();
            return;
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Export Graph Images");
        File initial = currentFile != null ? currentFile.getParentFile() : AppDirectories.get().saves().toFile();
        if (initial != null && initial.isDirectory()) {
            chooser.setInitialDirectory(initial);
        }
        File directory = chooser.showDialog(stage);
        if (directory == null) {
            return;
        }

        stage.getScene().setCursor(Cursor.WAIT);
        try {
            List<File> written = GraphImageExport.exportComponents(canvas, directory, exportBaseName());
            new Alert(Alert.AlertType.INFORMATION,
                    written.size() == 1
                            ? "Exported " + written.get(0).getName() + " to " + directory
                            : "Exported " + written.size() + " images to " + directory).showAndWait();
        } catch (IOException | RuntimeException ex) {
            log.error("Image export failed", ex);
            new Alert(Alert.AlertType.ERROR, "Failed to export images: " + ex.getMessage()).showAndWait();
        } finally {
            stage.getScene().setCursor(Cursor.DEFAULT);
        }
    }

    /** The open graph's filename without its extension, or a generic name when nothing has been saved yet. */
    private String exportBaseName() {
        if (currentFile == null) {
            return "graph";
        }
        String name = currentFile.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Saves the graph to {@code file} and records it as the current/last file. */
    private void saveTo(GraphCanvas canvas, File file) {
        try {
            // The catalog goes along so each node library this graph uses is recorded with the
            // repository it can be installed from, not just its id — that's what lets another
            // machine offer to fetch what's missing rather than only name it.
            io.github.jaymcole.housegraph.ui.io.GraphFileIO.save(canvas, file, pluginCatalog);
            rememberLastFile(file);
        } catch (IOException ex) {
            new Alert(Alert.AlertType.ERROR, "Failed to save graph: " + ex.getMessage()).showAndWait();
        }
    }

    /**
     * Records the just-saved/opened file as the current file and, unless this run was pointed at a
     * graph with {@code --graph}, as the one to reopen on the next launch and at the head of the
     * recent list. Save still targets it either way — {@link #trackLastFile} governs only what
     * is persisted for the next launch.
     *
     * <p>A supervised instance stays out of the recent list for the same reason it stays out of
     * {@link AppPreferences#LAST_FILE}: the graphs a daemon cycles through are not files the person
     * at this keyboard was working on.
     */
    private void rememberLastFile(File file) {
        currentFile = file;
        updateTitle();
        if (!trackLastFile) {
            return;
        }
        preferences.put(AppPreferences.LAST_FILE, file.getAbsolutePath());
        // Writes the store, the key just put included, so there is one write rather than two.
        RecentGraphs.remember(preferences, file);
    }

    /**
     * The single path both File ▸ Open and the startup reopen take.
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
     * <p><b>There is deliberately no auto-install here.</b> Installing without asking exists only in
     * the unattended daemon, where the operator hand-wrote the repository URL the graphs come from
     * and that naming <em>is</em> the trust decision — see {@code RemoteDeployment} and
     * {@code docs/engine/plugin-runtime.md}. On the desktop the file may have arrived from anywhere,
     * so it can propose a code download but never cause one.
     *
     * <p>Opening with missing libraries is safe because their nodes are preserved verbatim (see
     * {@code MissingNode}). Before that fix, "open anyway" would have been a data-loss trap.
     */
    private void openGraph(File file, boolean interactive) {
        JSONObject root;
        try {
            root = GraphFileIO.readRoot(file);
        } catch (IOException | RuntimeException ex) {
            reportOpenFailure(file, interactive, ex);
            return;
        }

        GraphDependencyCheck.DependencyReport report = GraphDependencyCheck.inspect(root, pluginCatalog);
        if (!report.isSatisfied()) {
            if (interactive && !confirmOpenWithMissingLibraries(report.blocking())) {
                return;
            }
            showMissingLibrariesNotice(report.blocking());
        } else {
            hideMissingLibrariesNotice();
        }

        try {
            canvas.loadSnapshot(GraphFileIO.fromRoot(root, nodeRegistry));
            canvas.setCameraState(GraphFileIO.cameraFromJson(root));
            rememberLastFile(file);
        } catch (RuntimeException ex) {
            reportOpenFailure(file, interactive, ex);
        }
    }

    private void reportOpenFailure(File file, boolean interactive, Exception ex) {
        if (interactive) {
            new Alert(Alert.AlertType.ERROR, "Failed to load graph: " + ex.getMessage()).showAndWait();
        } else {
            log.error("Could not reopen last file " + file, ex);
        }
    }

    /** @return true to go ahead and open the graph */
    private boolean confirmOpenWithMissingLibraries(List<GraphDependencyCheck.RequiredPlugin> blocking) {
        StringBuilder detail = new StringBuilder();
        for (GraphDependencyCheck.RequiredPlugin required : blocking) {
            detail.append("  • ").append(required.label());
            if (required.repository() != null) {
                detail.append("  —  ").append(required.repository());
            }
            detail.append('\n');
        }

        ButtonType openAnyway = new ButtonType("Open anyway", ButtonBar.ButtonData.OK_DONE);
        ButtonType install = new ButtonType("Install and open", ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

        boolean anyInstallable = blocking.stream().anyMatch(GraphDependencyCheck.RequiredPlugin::isInstallable);
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
            blocking.stream()
                    .filter(GraphDependencyCheck.RequiredPlugin::isInstallable)
                    .map(GraphDependencyCheck.RequiredPlugin::repository)
                    .forEach(repository -> PluginWindow.showAndInstall(pluginCatalog, preferences,
                            this::tryReloadNodeLibraries, canvas::countLiveNodesFrom, repository));
        }
        return true;
    }

    /** Puts the open file's name in the window title, the way a document app does. */
    private void updateTitle() {
        stage.setTitle(currentFile == null ? "HouseGraph" : currentFile.getName() + " — HouseGraph");
    }

    private void hideMissingLibrariesNotice() {
        missingLibrariesNotice.setVisible(false);
        missingLibrariesNotice.setManaged(false);
    }

    private void showMissingLibrariesNotice(List<GraphDependencyCheck.RequiredPlugin> blocking) {
        int count = blocking.size();
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
