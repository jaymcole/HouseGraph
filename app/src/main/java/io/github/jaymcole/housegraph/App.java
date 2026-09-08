package io.github.jaymcole.housegraph;

import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.logging.Logging;
import io.github.jaymcole.housegraph.modules.ModuleLibrary;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.plugin.PluginInstaller;
import io.github.jaymcole.housegraph.plugin.PluginLoader;
import io.github.jaymcole.housegraph.storage.AppDirectories;
import io.github.jaymcole.housegraph.storage.AppPreferences;
import io.github.jaymcole.housegraph.ui.io.RecentGraphs;
import io.github.jaymcole.housegraph.ui.log.LogLevelPreferences;
import io.github.jaymcole.housegraph.ui.plugin.PluginWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * JavaFX application entry point for HouseGraph: the process-wide services, and the set of editor
 * windows open on top of them.
 *
 * <h2>What lives here and what lives in a window</h2>
 * This class owns the things there is exactly one of per process — the preferences store, the
 * node-library catalog and class loader, the node registry, the module library — and the list of
 * open {@link GraphWindow}s. A window owns everything a person can have several of at once: a
 * stage, a {@link NodeGraph}, a canvas, an open file, an undo history. Commands the menus issue are
 * a window's business; the few that are inherently app-wide — reloading node libraries, quitting,
 * opening another window — come back here.
 *
 * <p>{@code ResourceRegistry.shared()} stays process-wide across all of them, deliberately: a
 * long-lived resource is referenced by name rather than wired, so one window's camera node is
 * reachable from another window's listener.
 *
 * <h2>Arguments</h2>
 * Launched bare, the app reopens whatever {@link AppPreferences#LAST_FILE} holds. One named
 * parameter exists for running it under a supervisor (see {@code remote/} and
 * {@code docs/engine/remote-runtime.md}):
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
 * {@link #installShutdownHook} closes that gap. Closing the last editor window quits too, the way a
 * document-based desktop app does.
 */
public class App extends Application {

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
     *
     * <p>Several open windows do not multiply it either: each window's graph is disposed in turn, but
     * the release pass inside each is already concurrent and a person does not run many windows'
     * worth of wedged servers at once.
     */
    private static final long SHUTDOWN_TIMEOUT_SECONDS =
            NodeGraph.DEFAULT_RELEASE_TIMEOUT.toSeconds() + 10;

    private final AppPreferences preferences = AppPreferences.load();

    /** Counted down at the end of {@link #stop()}, so the shutdown hook knows teardown finished. */
    private final CountDownLatch stopped = new CountDownLatch(1);

    /** Every editor window currently on screen, in the order they were opened. */
    private final List<GraphWindow> windows = new ArrayList<>();

    private NodeRegistry nodeRegistry;
    private PluginCatalog pluginCatalog;
    private PluginLoader pluginLoader;
    /** Where a published module is found, and what a saved {@code modules} row is written from. */
    private ModuleLibrary moduleLibrary;

    /**
     * Whether this run may write {@link AppPreferences#LAST_FILE}. False when a graph was named with
     * {@code --graph}: a supervised instance must not overwrite what the person at the keyboard had
     * open, and on a machine running several graphs at once there is no single "last" file to record.
     */
    private boolean trackLastFile = true;

    /**
     * Set once the app is on its way out, so the per-window close handler that normally quits on the
     * last window does not ask for a second exit while the first is in progress.
     */
    private boolean exiting;

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

        nodeRegistry = new NodeRegistry(pluginLoader.scanRoots());
        // Published modules, resolved through the same registry the canvas builds nodes with, so a
        // module made of a library's nodes resolves them rather than loading placeholders. The scan
        // itself is lazy — nothing has touched the modules directory yet.
        moduleLibrary = ModuleLibrary.defaultLibrary(nodeRegistry);

        // The primary stage becomes the first editor window; every one after it gets a fresh Stage.
        // Nothing else distinguishes them — the first window is not privileged, and closing it while
        // others are open leaves the app running.
        GraphWindow first = openWindow(stage);

        installShutdownHook();

        // A graph named on the command line wins over the remembered one. Non-interactive either
        // way — see GraphWindow.openGraph.
        Optional<File> requested = requestedGraph();
        trackLastFile = requested.isEmpty();
        requested.filter(file -> !file.isFile())
                .ifPresent(file -> log.error("No graph file at {}", file.getAbsolutePath()));
        requested.or(() -> preferences.get(AppPreferences.LAST_FILE).map(File::new))
                .filter(File::isFile)
                .ifPresent(file -> first.openGraph(file, false));
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

    // --- Windows -------------------------------------------------------------------

    /**
     * Opens an editor window on an empty canvas and shows it.
     *
     * @return the new window, for the caller that has something to load into it
     */
    GraphWindow openEmptyWindow() {
        return openWindow(new Stage());
    }

    /**
     * Fills {@code stage} with a new editor window, registers it and shows it, stepped down and
     * right of the window opened most recently so it doesn't land exactly on top of it.
     */
    private GraphWindow openWindow(Stage stage) {
        GraphWindow window = new GraphWindow(this, stage);
        if (!windows.isEmpty()) {
            window.cascadeFrom(windows.get(windows.size() - 1));
        }
        windows.add(window);
        window.show();
        return window;
    }

    /**
     * Opens {@code file} in a window of its own.
     *
     * <p>A file already open somewhere raises that window instead of opening a second view of it:
     * two windows on one file would each save the whole canvas, so whichever saved last would
     * silently discard the other's work. A window opened for a file that then fails to load, or
     * whose missing-library prompt is cancelled, is closed again rather than left behind empty.
     */
    void openInNewWindow(File file) {
        Optional<GraphWindow> existing = windowShowing(file);
        if (existing.isPresent()) {
            existing.get().toFront();
            return;
        }
        GraphWindow window = openEmptyWindow();
        if (!window.openGraph(file, true)) {
            window.close();
        }
    }

    /** The window {@code file} is already open in, if any; compared by absolute path. */
    private Optional<GraphWindow> windowShowing(File file) {
        File wanted = file.getAbsoluteFile();
        return windows.stream()
                .filter(window -> window.openFile()
                        .map(open -> open.getAbsoluteFile().equals(wanted))
                        .orElse(false))
                .findFirst();
    }

    /**
     * Disposes the graph of a window that has just been hidden, and quits when it was the last one.
     *
     * <p>Disposing here rather than in {@link #stop()} is what keeps one window's shutdown from
     * touching another's nodes: a closed window's timers, connections and child processes go away
     * with it while every other window keeps running. {@code stop()} still disposes whatever is
     * left, for the paths that never hide a stage.
     */
    void windowClosed(GraphWindow window) {
        if (!windows.remove(window)) {
            return;
        }
        window.graph().dispose();
        if (windows.isEmpty()) {
            exit();
        }
    }

    /** Quits the app: every window closes and {@link #stop()} runs, disposing what is still open. */
    void exit() {
        if (exiting) {
            return;
        }
        exiting = true;
        Platform.exit();
    }

    // --- Shared services, for the windows ---------------------------------------------

    AppPreferences preferences() {
        return preferences;
    }

    PluginCatalog pluginCatalog() {
        return pluginCatalog;
    }

    NodeRegistry nodeRegistry() {
        return nodeRegistry;
    }

    ModuleLibrary moduleLibrary() {
        return moduleLibrary;
    }

    /** A node library's human name for the node search box, or null when nothing is installed under that id. */
    String libraryName(String pluginId) {
        return pluginCatalog.byId(pluginId).map(PluginCatalog.Installed::name).orElse(null);
    }

    /** Opens a URL or a folder in the desktop's browser or file manager. */
    void showDocument(String uri) {
        getHostServices().showDocument(uri);
    }

    void openPluginWindow() {
        PluginWindow.show(pluginCatalog, preferences, this::tryReloadNodeLibraries, this::countLiveNodesFrom);
    }

    void openPluginWindowAndInstall(String repository) {
        PluginWindow.showAndInstall(pluginCatalog, preferences, this::tryReloadNodeLibraries,
                this::countLiveNodesFrom, repository);
    }

    // --- Files ------------------------------------------------------------------------

    List<File> recentGraphs() {
        return RecentGraphs.load(preferences);
    }

    void clearRecentGraphs() {
        RecentGraphs.clear(preferences);
    }

    /**
     * Records a just-saved or just-opened file as the one to reopen on the next launch and at the
     * head of the recent list — unless this run was pointed at a graph with {@code --graph}. Which
     * file a window's Save targets is the window's own business either way; {@link #trackLastFile}
     * governs only what is persisted.
     *
     * <p>A supervised instance stays out of the recent list for the same reason it stays out of
     * {@link AppPreferences#LAST_FILE}: the graphs a daemon cycles through are not files the person
     * at this keyboard was working on.
     *
     * <p>With several windows open, the last file written or opened in any of them wins. That is what
     * "the file I was last working on" means, and it is the same rule the recent list follows.
     */
    void rememberOpenedFile(File file) {
        if (!trackLastFile) {
            return;
        }
        preferences.put(AppPreferences.LAST_FILE, file.getAbsolutePath());
        // Writes the store, the key just put included, so there is one write rather than two.
        RecentGraphs.remember(preferences, file);
    }

    // --- Node libraries ----------------------------------------------------------------

    /** How many nodes from {@code pluginId} are live, counted across every open window. */
    int countLiveNodesFrom(String pluginId) {
        return windows.stream().mapToInt(window -> window.canvas().countLiveNodesFrom(pluginId)).sum();
    }

    /**
     * Rebuilds everything that depends on the set of installed node libraries, after one is
     * installed, removed, enabled or disabled — but only when it's safe to. The old loader is closed
     * and a new one built from the current catalog, which re-scans and re-loads the classes of
     * <b>every</b> enabled library, not just the one that changed. That's only safe while no node
     * from <b>any</b> library is on <b>any</b> canvas: a live node would stay bound to its old
     * loader's {@code Class} object while the registry now knows only the new one, so the same type
     * would exist twice and {@code duplicate()} would clone the wrong one. Every window is asked,
     * not just the one whose menu opened the library manager — one window's live node is as much a
     * stale binding as another's.
     * <p>
     * When it isn't safe, the catalog/disk change the caller already made (a JSON write, or a jar
     * installed to a fresh version-stamped path) is left as-is and simply doesn't take effect until
     * the next restart, which calls {@link #start} and reads the catalog fresh.
     *
     * @return true if the reload actually ran; false if it was skipped because a library node is live
     */
    public boolean tryReloadNodeLibraries() {
        if (windows.stream().anyMatch(window -> window.canvas().hasLiveLibraryNodes())) {
            return false;
        }
        if (pluginLoader != null) {
            pluginLoader.close();
        }
        pluginLoader = PluginLoader.from(pluginCatalog, App.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(pluginLoader.classLoader());
        nodeRegistry.setRoots(pluginLoader.scanRoots());
        // Every module interface in the index was derived with the old set of node types, so a module
        // built from the library that just arrived would still read as placeholders until re-derived.
        moduleLibrary.refresh();
        windows.forEach(window -> window.canvas().reloadNodeTypes());
        return true;
    }

    @Override
    public void stop() {
        exiting = true;
        try {
            // App is closing: dispose every graph still open so any long-lived node resources
            // (timers, connections) are shut down cleanly rather than leaked. Windows closed one at
            // a time have already disposed theirs, and a second dispose of an emptied graph is
            // harmless, so this needs no coordination with windowClosed.
            for (GraphWindow window : new ArrayList<>(windows)) {
                window.graph().dispose();
            }
            windows.clear();
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

    public static void main(String[] args) {
        launch(args);
    }

}
