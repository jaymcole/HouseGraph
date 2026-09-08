package io.github.jaymcole.housegraph;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleNode;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.modules.ModuleChoices;
import io.github.jaymcole.housegraph.modules.ModuleFile;
import io.github.jaymcole.housegraph.modules.ModuleLibrary;
import io.github.jaymcole.housegraph.modules.ModulePublisher;
import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck;
import io.github.jaymcole.housegraph.saveformat.GraphFileIO;
import io.github.jaymcole.housegraph.storage.AppDirectories;
import io.github.jaymcole.housegraph.ui.GraphCanvas;
import io.github.jaymcole.housegraph.ui.editor.SecretsEditor;
import io.github.jaymcole.housegraph.ui.export.GraphImageExport;
import io.github.jaymcole.housegraph.ui.log.LogWindow;
import io.github.jaymcole.housegraph.ui.menu.MainMenuBar;
import io.github.jaymcole.housegraph.ui.menu.MenuActions;
import io.github.jaymcole.housegraph.ui.module.ModulePickerDialog;
import javafx.concurrent.Task;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One editor window: a stage, the graph open in it, and the canvas drawing that graph.
 *
 * <h2>Why this is not {@code App}</h2>
 * Everything a person can have several of at once lives here; everything there is exactly one of
 * per process — the preferences store, the node-library catalog and loader, the node registry, the
 * module library — stays on {@link App}, which hands this class the ones it needs. That split is
 * what makes a second window cost a second {@link NodeGraph} and a second canvas rather than a
 * second copy of the application. A window reaches back through {@code app} for anything shared,
 * and {@code App} reaches into a window only through the few questions that have to span all of
 * them (see {@link App#tryReloadNodeLibraries}).
 *
 * <h2>Documents, not tabs</h2>
 * Each window is an independent document: its own open file, its own undo history, its own
 * missing-library notice, its own Watch Speed. Closing one disposes only its graph. Closing the
 * last one quits the app, the way a document-based desktop app behaves.
 *
 * <p>What windows share besides the services above is {@code ResourceRegistry.shared()} — named
 * long-lived resources are process-wide by design, so a resource registered by a node in one window
 * is visible to listeners in another.
 *
 * <h2>The window's chrome</h2>
 * A menu bar over a short toolbar over the canvas. The menus are built by {@link MainMenuBar},
 * which this class serves as {@link MenuActions}: every command that needs the stage or a shared
 * service is a method here, and everything that acts on the open graph the menu bar calls straight
 * on {@link GraphCanvas}. The toolbar is only a shortcut strip — each of its buttons has a menu
 * item, so nothing lives there alone.
 */
final class GraphWindow implements MenuActions {

    private static final Logger log = Log.get(GraphWindow.class);

    /** How far each successive window is offset from the last, so a new one never hides its parent. */
    private static final double CASCADE_STEP = 28;

    private final App app;
    private final Stage stage;
    private final NodeGraph graph;
    private final GraphCanvas canvas;

    /**
     * Shown in the toolbar when the graph opened here needs node libraries that aren't installed. A
     * notice rather than a dialog when nobody asked for the file — startup reopen must never block,
     * and it runs after the window is already on screen.
     */
    private final Hyperlink missingLibrariesNotice;

    /** The file most recently saved to or loaded from; what File ▸ Save writes to. Null until chosen. */
    private File currentFile;

    /**
     * Builds the window and its canvas but does not show it — the caller does that, so a window
     * opened for a particular file can be abandoned before it ever appears if that file turns out
     * to be unopenable.
     *
     * @param app   the shared services, and the registry this window reports its closing to
     * @param stage the stage to fill; the primary one for the first window, a fresh one after that
     */
    GraphWindow(App app, Stage stage) {
        this.app = app;
        this.stage = stage;
        this.graph = new NodeGraph();
        // The node search box resolves a library id to its human name through the same catalog the
        // library window edits, so a renamed or reinstalled library is reflected without the canvas
        // needing its own copy of that mapping. The module library goes along for two things:
        // resolving a loaded graph's module references, and Add Module….
        this.canvas = new GraphCanvas(graph, app.nodeRegistry(), app::libraryName,
                app.moduleLibrary(), this::chooseModule);

        missingLibrariesNotice = new Hyperlink();
        missingLibrariesNotice.setVisible(false);
        missingLibrariesNotice.setManaged(false);
        missingLibrariesNotice.setStyle("-fx-text-fill: #ff6b6b;");
        missingLibrariesNotice.setOnAction(e -> app.openPluginWindow());

        BorderPane root = new BorderPane();
        root.setTop(new VBox(new MainMenuBar(canvas, this), buildToolBar()));
        root.setCenter(canvas);

        updateTitle();
        stage.setScene(new Scene(root, 1100, 750));
        // Hidden rather than closed, because Platform.exit() hides every stage as well: routing both
        // through one handler means a graph is disposed exactly once whichever way its window goes.
        stage.setOnHidden(event -> app.windowClosed(this));
    }

    /**
     * Steps this window down and right of {@code previous}, so a new window does not land exactly on
     * top of the one it was opened from. Call before {@link #show()}.
     *
     * <p>Measured from a window already on screen rather than from a count, because an unshown
     * stage's x and y are {@code NaN} until the platform places it — offsetting from those would
     * leave the position unset rather than stepped. A step that would push the title bar off the
     * screen is skipped, and the platform's own placement stands.
     */
    void cascadeFrom(GraphWindow previous) {
        double x = previous.stage.getX() + CASCADE_STEP;
        double y = previous.stage.getY() + CASCADE_STEP;
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return;
        }
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        if (!screen.contains(x, y)) {
            return;
        }
        stage.setX(x);
        stage.setY(y);
    }

    /** Puts this window on screen. */
    void show() {
        stage.show();
    }

    /** Raises this window and gives it focus — what asking to open an already-open file does. */
    void toFront() {
        stage.setIconified(false);
        stage.toFront();
        stage.requestFocus();
    }

    /** Hides this window, which disposes its graph through the stage's hidden handler. */
    void close() {
        stage.close();
    }

    /** The graph open in this window; {@link App} disposes it when the window closes or the app stops. */
    NodeGraph graph() {
        return graph;
    }

    /** The canvas in this window, for the few questions that have to be asked of every window. */
    GraphCanvas canvas() {
        return canvas;
    }

    /** The file open in this window, empty when nothing has been opened or saved into it yet. */
    Optional<File> openFile() {
        return Optional.ofNullable(currentFile);
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
    public void newWindow() {
        app.openEmptyWindow();
    }

    @Override
    public void openGraph() {
        File file = createFileChooser("Open Graph").showOpenDialog(stage);
        if (file != null) {
            openGraph(file, true);
        }
    }

    @Override
    public void openGraphInNewWindow() {
        File file = createFileChooser("Open Graph in New Window").showOpenDialog(stage);
        if (file != null) {
            app.openInNewWindow(file);
        }
    }

    @Override
    public List<File> recentGraphs() {
        return app.recentGraphs();
    }

    @Override
    public void openRecentGraph(File file) {
        // The user picked this file, so it takes the same interactive path as File ▸ Open: a missing
        // node library prompts rather than quietly leaving a notice.
        openGraph(file, true);
    }

    @Override
    public void clearRecentGraphs() {
        app.clearRecentGraphs();
    }

    @Override
    public void saveGraph() {
        // Writes straight to the current file with no dialog. Until one has been chosen (fresh
        // window, never saved), it falls back to the Save-As flow.
        if (currentFile == null) {
            saveAs();
            return;
        }
        saveTo(currentFile);
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
    public void closeWindow() {
        close();
    }

    @Override
    public void exit() {
        // Quits the whole app, every window included, along the same path the shutdown hook takes,
        // so stop() runs and every graph is disposed rather than left to the OS.
        app.exit();
    }

    @Override
    public void editSecrets() {
        SecretsEditor.show(stage);
    }

    @Override
    public void manageNodeLibraries() {
        app.openPluginWindow();
    }

    @Override
    public void showLogs() {
        // The log window lives in its own top-level stage (not owned by any editor window) so it
        // survives independently and can be closed and reopened without losing history — the buffer
        // keeps capturing while it's shut.
        LogWindow.show(app.preferences());
    }

    @Override
    public void openDataFolder() {
        app.showDocument(AppDirectories.get().root().toUri().toString());
    }

    @Override
    public void openDocumentation() {
        app.showDocument(MainMenuBar.documentationUrl());
    }

    @Override
    public void setStepDelayMillis(long millis) {
        // Slows every flow-driven run down to something the eye can follow: the canvas already
        // animates each node and edge as it fires, this just spaces the firings out. Per window as
        // well as session-only, and off by default — it changes timing, so it is a thing you switch
        // on to look at one graph, not a setting a graph or a deployment should carry (see
        // NodeGraph.setStepDelayMillis).
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

    /** Prompts for a destination file, then saves the graph there. */
    private void saveAs() {
        File file = createFileChooser("Save Graph").showSaveDialog(stage);
        if (file == null) {
            return;
        }
        saveTo(file);
    }

    // --- Modules -------------------------------------------------------------------

    /**
     * Writes the canvas into the modules directory and gives it a stable id.
     *
     * <p>Save-As shaped, and deliberately so: publishing writes a file, and the file it writes
     * becomes the open document, so the next File ▸ Save edits the module rather than leaving the
     * canvas and the module to drift apart. Re-publishing an already-published module over its own
     * file keeps the id — {@code ui.io.GraphFileIO} carries it across the write and
     * {@code ModuleLibrary.publish} mints one only when there is none.
     *
     * <p>Refused before the dialog when the canvas declares no interface, because there is nothing to
     * ask about; every other refusal needs the file on disk and comes back from
     * {@link ModulePublisher#publish}. The scan and the id write go to a worker: they are filesystem
     * work, and the FX thread may not wait on it.
     */
    @Override
    public void publishAsModule() {
        if (!ModulePublisher.declaresInterface(canvas.snapshotAll())) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "A module needs at least one Module Input, Module Output, Module Entry or Module"
                            + " Exit node: those are what become its ports. Add one and publish again.");
            alert.initOwner(stage);
            alert.setHeaderText("This graph has no module interface.");
            alert.showAndWait();
            return;
        }

        FileChooser chooser = createFileChooser("Publish as Module");
        chooser.setInitialDirectory(AppDirectories.get().modules().toFile());
        chooser.setInitialFileName(currentFile == null ? "module.json" : currentFile.getName());
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }

        ModuleLibrary moduleLibrary = app.moduleLibrary();
        try {
            io.github.jaymcole.housegraph.ui.io.GraphFileIO.save(
                    canvas, file, app.pluginCatalog(), moduleLibrary);
        } catch (IOException ex) {
            new Alert(Alert.AlertType.ERROR, "Failed to write the module: " + ex.getMessage()).showAndWait();
            return;
        }
        rememberFile(file);

        Task<ModulePublisher.Result> publishing = new Task<>() {
            @Override
            protected ModulePublisher.Result call() {
                // Refreshed first so a module added or edited on disk since this session started is
                // in the index the cycle check reads, and so the new module is not the only thing in it.
                moduleLibrary.refresh();
                return ModulePublisher.publish(file.toPath(), moduleLibrary);
            }
        };
        publishing.setOnSucceeded(event -> reportPublish(publishing.getValue()));
        publishing.setOnFailed(event -> {
            log.error("Publishing " + file + " failed", publishing.getException());
            new Alert(Alert.AlertType.ERROR, "Failed to publish the module: "
                    + publishing.getException()).showAndWait();
        });
        runInBackground(publishing, "housegraph-publish-module");
    }

    /** Renders one {@link ModulePublisher.Result}; the decisions in it were all made headlessly. */
    private void reportPublish(ModulePublisher.Result result) {
        if (!result.isPublished()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, result.reason());
            alert.initOwner(stage);
            alert.setHeaderText("This graph cannot be published as a module.");
            alert.getDialogPane().setMinWidth(560);
            alert.showAndWait();
            return;
        }
        StringBuilder detail = new StringBuilder("Other graphs can now add it with Add Module…");
        for (String warning : result.warnings()) {
            detail.append("\n\n").append(warning);
        }
        Alert alert = new Alert(result.warnings().isEmpty()
                ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING, detail.toString());
        alert.initOwner(stage);
        alert.setHeaderText("\"" + result.module().name() + "\" is published as a module.");
        alert.getDialogPane().setMinWidth(560);
        alert.showAndWait();
    }

    /**
     * The canvas's Add Module… command: loads what is on offer, asks, and hands back a node already
     * bound to the answer.
     *
     * <p>The load is a directory scan and a node build per module file, so it runs on a worker and the
     * dialog opens from its success handler, back on the FX thread. The graph being edited is left
     * out of the offer — a graph referencing itself is a cycle nothing could load.
     */
    private void chooseModule(Consumer<BaseNode> place) {
        // Read here rather than from the worker: currentFile is FX-thread state.
        File open = currentFile;
        ModuleLibrary moduleLibrary = app.moduleLibrary();
        Task<List<ModuleChoices.Choice>> loading = new Task<>() {
            @Override
            protected List<ModuleChoices.Choice> call() {
                moduleLibrary.refresh();
                return ModuleChoices.offer(moduleLibrary.all(), moduleIdOf(open));
            }
        };
        loading.setOnSucceeded(event -> ModulePickerDialog.show(stage, loading.getValue())
                .ifPresent(choice -> place.accept(referenceTo(choice))));
        loading.setOnFailed(event -> {
            log.error("Could not list the published modules", loading.getException());
            new Alert(Alert.AlertType.ERROR, "Could not read the modules directory: "
                    + loading.getException()).showAndWait();
        });
        runInBackground(loading, "housegraph-list-modules");
    }

    /** A module node pointed at {@code choice} and already resolved, so it arrives on the canvas with ports. */
    private ModuleNode referenceTo(ModuleChoices.Choice choice) {
        ModuleNode node = new ModuleNode();
        node.setModuleId(choice.id());
        node.bindTo(app.moduleLibrary());
        return node;
    }

    /**
     * The module id of {@code file}, or null when it has none — what must not be offered as a module
     * of itself.
     *
     * <p>Read from the file rather than remembered, because publishing writes the id straight into it
     * and nothing on the canvas carries it. Called from the worker, with the rest of the listing.
     */
    private static String moduleIdOf(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return ModuleFile.idOf(GraphFileIO.readRoot(file));
        } catch (IOException | RuntimeException e) {
            // Not knowing costs one row of the picker being offered that should not be, which the
            // publish-time cycle check would still refuse. Not worth failing the listing over.
            log.debug("Could not read {} to exclude it from the module picker: {}", file, e.toString());
            return null;
        }
    }

    /** Runs one background task on a daemon thread, so a pending scan can never hold the JVM open. */
    private static void runInBackground(Task<?> task, String threadName) {
        Thread worker = new Thread(task, threadName);
        worker.setDaemon(true);
        worker.start();
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

    /** Saves the graph to {@code file} and records it as this window's current file. */
    private void saveTo(File file) {
        try {
            // The catalog goes along so each node library this graph uses is recorded with the
            // repository it can be installed from, not just its id — that's what lets another
            // machine offer to fetch what's missing rather than only name it. The module library is
            // there for the same reason one level further out: a referenced module's own libraries
            // are recorded in its row, and this graph's own nodes could never name them.
            io.github.jaymcole.housegraph.ui.io.GraphFileIO.save(
                    canvas, file, app.pluginCatalog(), app.moduleLibrary());
            rememberFile(file);
        } catch (IOException ex) {
            new Alert(Alert.AlertType.ERROR, "Failed to save graph: " + ex.getMessage()).showAndWait();
        }
    }

    /**
     * Records the just-saved or just-opened file as this window's current file, and hands it to the
     * app to persist as the one to reopen on the next launch and at the head of the recent list.
     * Save targets it either way — what gets persisted is the app's decision, see
     * {@link App#rememberOpenedFile}.
     */
    private void rememberFile(File file) {
        currentFile = file;
        updateTitle();
        app.rememberOpenedFile(file);
    }

    /**
     * The single path File ▸ Open, Open Recent, a window opened on a file and the startup reopen
     * all take.
     *
     * <h4>Missing node libraries</h4>
     * Before building anything, the file's root {@code plugins} table is compared against what's
     * installed — one pass, no class loading. What happens when something is missing depends on who
     * asked:
     *
     * <ul>
     *   <li><b>The user chose the file</b> ({@code interactive}): a dialog listing what's missing,
     *       offering to open anyway or to install from the repository the file recorded. Never
     *       installs silently — a save file is untrusted input proposing a code download.</li>
     *   <li><b>Startup reopen</b>: never blocks, never touches the network. This runs after the
     *       window is shown, so a modal would appear over an already-rendered canvas, and someone
     *       reopening the app wants to see their graph rather than a network-dependent prompt. A
     *       toolbar notice points at the library window instead.</li>
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
     *
     * @return true if the graph is now on this window's canvas; false if the file could not be read
     *         or the user cancelled — which is what tells a window opened <em>for</em> that file
     *         that it has no reason to stay
     */
    boolean openGraph(File file, boolean interactive) {
        JSONObject root;
        try {
            root = GraphFileIO.readRoot(file);
        } catch (IOException | RuntimeException ex) {
            reportOpenFailure(file, interactive, ex);
            return false;
        }

        GraphDependencyCheck.DependencyReport report =
                GraphDependencyCheck.inspect(root, app.pluginCatalog());
        if (!report.isSatisfied()) {
            if (interactive && !confirmOpenWithMissingLibraries(report.blocking())) {
                return false;
            }
            showMissingLibrariesNotice(report.blocking());
        } else {
            hideMissingLibrariesNotice();
        }

        try {
            canvas.loadSnapshot(GraphFileIO.fromRoot(root, app.nodeRegistry()));
            canvas.setCameraState(GraphFileIO.cameraFromJson(root));
            rememberFile(file);
            return true;
        } catch (RuntimeException ex) {
            reportOpenFailure(file, interactive, ex);
            return false;
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
                    .forEach(app::openPluginWindowAndInstall);
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
}
