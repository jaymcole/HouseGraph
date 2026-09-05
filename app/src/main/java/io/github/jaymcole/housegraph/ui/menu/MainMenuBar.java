package io.github.jaymcole.housegraph.ui.menu;

import io.github.jaymcole.housegraph.ui.GraphCanvas;
import io.github.jaymcole.housegraph.ui.io.RecentGraphs;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import java.io.File;
import java.util.List;

/**
 * The application menu bar: File, Edit, View, Run, Tools, Help.
 *
 * <h2>Where each command comes from</h2>
 * Two sources, and the split is the reason this class can exist at all. Everything that acts on the
 * open graph — undo, copy, zoom, select-all — is a method on the {@link GraphCanvas} it is given.
 * Everything that needs the window, the preferences store or the plugin catalog is a method on
 * {@link MenuActions}, which the application implements. Nothing here reaches into {@code App}.
 *
 * <h2>Accelerators and the canvas's own key handling</h2>
 * The canvas handles Delete, Ctrl/Cmd+C/V/Z/Shift+Z/A itself and <b>consumes</b> those events, and
 * JavaFX processes a scene's accelerators only after the event has bubbled unconsumed. So while the
 * canvas has focus its handler wins and the identical accelerator never fires; the accelerator is
 * what makes the same keys work when focus is somewhere else — a toolbar button, say. Duplication
 * is deliberate, not an oversight: the accelerators are also what puts the shortcut hint next to
 * each menu item, which is where most people find out the shortcut exists. A text field being
 * edited consumes these keys too, so typing in a node's inline editor never reaches the menu.
 *
 * <h2>Enablement</h2>
 * Items whose command would be a no-op are greyed out rather than silently doing nothing, and the
 * state is refreshed in {@code setOnShowing} on the menu that owns them. There is no model to
 * observe — undo depth, selection and clipboard are plain state on the canvas — and a menu that is
 * not open cannot be looked at, so recomputing as it opens is both sufficient and cheap.
 */
public class MainMenuBar extends MenuBar {

    /** Where Help ▸ Documentation goes. */
    private static final String DOCUMENTATION_URL = "https://github.com/jaymcole/HouseGraph#readme";

    private final GraphCanvas canvas;
    private final MenuActions actions;

    public MainMenuBar(GraphCanvas canvas, MenuActions actions) {
        this.canvas = canvas;
        this.actions = actions;
        // macOS puts the menus in the screen-top system bar, where a Mac user looks for them.
        // Ignored on every other platform, so it costs nothing to ask for unconditionally.
        setUseSystemMenuBar(true);
        getMenus().addAll(fileMenu(), editMenu(), viewMenu(), runMenu(), toolsMenu(), helpMenu());
    }

    private Menu fileMenu() {
        MenuItem newGraph = item("New Graph", shortcut(KeyCode.N), actions::newGraph);
        MenuItem open = item("Open…", shortcut(KeyCode.O), actions::openGraph);

        // Rebuilt every time the submenu opens, so it reflects whatever has been saved or loaded
        // since — including by another instance of the app. Populated once here as well, because a
        // Menu with no items silently refuses to open its popup, and "no items" is exactly the
        // state a fresh profile starts in.
        Menu recent = new Menu("Open Recent");
        populateRecent(recent);
        recent.setOnShowing(event -> populateRecent(recent));

        MenuItem save = item("Save", shortcut(KeyCode.S), actions::saveGraph);
        MenuItem saveAs = item("Save As…", shortcut(KeyCode.S, KeyCombination.SHIFT_DOWN), actions::saveGraphAs);
        MenuItem export = item("Export Images…", shortcut(KeyCode.E), actions::exportImages);
        MenuItem exit = item("Exit", shortcut(KeyCode.Q), actions::exit);

        Menu menu = new Menu("File");
        menu.getItems().addAll(newGraph, open, recent, new SeparatorMenuItem(),
                save, saveAs, new SeparatorMenuItem(),
                export, new SeparatorMenuItem(), exit);
        // Save reads "Save" once there is a file to write and "Save…" before then, because until
        // one has been chosen it prompts — the ellipsis is the only warning that it will.
        menu.setOnShowing(event -> save.setText(actions.hasCurrentFile() ? "Save" : "Save…"));
        return menu;
    }

    /**
     * Rebuilds the Open Recent submenu from what is on disk.
     *
     * <p>An entry whose file is gone is shown disabled and marked rather than dropped: the list is
     * not pruned (see {@link RecentGraphs}), so an unplugged drive greys its graphs out for as long
     * as it is away instead of losing them.
     */
    private void populateRecent(Menu menu) {
        menu.getItems().clear();

        List<File> recent = actions.recentGraphs();
        if (recent.isEmpty()) {
            MenuItem placeholder = new MenuItem("No recent graphs");
            placeholder.setDisable(true);
            menu.getItems().add(placeholder);
            return;
        }

        for (File file : recent) {
            boolean missing = !file.isFile();
            String label = RecentGraphs.describe(file);
            MenuItem entry = new MenuItem(missing ? label + "  (missing)" : label);
            entry.setDisable(missing);
            entry.setOnAction(event -> actions.openRecentGraph(file));
            menu.getItems().add(entry);
        }

        MenuItem clear = new MenuItem("Clear Recent Graphs");
        clear.setOnAction(event -> actions.clearRecentGraphs());
        menu.getItems().addAll(new SeparatorMenuItem(), clear);
    }

    private Menu editMenu() {
        MenuItem undo = item("Undo", shortcut(KeyCode.Z), canvas::undo);
        MenuItem redo = item("Redo", shortcut(KeyCode.Z, KeyCombination.SHIFT_DOWN), canvas::redo);
        MenuItem copy = item("Copy", shortcut(KeyCode.C), canvas::copySelection);
        MenuItem paste = item("Paste", shortcut(KeyCode.V), canvas::pasteClipboard);
        MenuItem delete = item("Delete", new KeyCodeCombination(KeyCode.DELETE), canvas::deleteSelected);
        MenuItem selectAll = item("Select All", shortcut(KeyCode.A), canvas::selectAll);

        Menu menu = new Menu("Edit");
        menu.getItems().addAll(undo, redo, new SeparatorMenuItem(),
                copy, paste, delete, new SeparatorMenuItem(), selectAll);
        menu.setOnShowing(event -> {
            undo.setDisable(!canvas.canUndo());
            redo.setDisable(!canvas.canRedo());
            copy.setDisable(!canvas.hasSelection());
            paste.setDisable(!canvas.canPaste());
            delete.setDisable(!canvas.hasSelection());
        });
        return menu;
    }

    private Menu viewMenu() {
        // EQUALS rather than PLUS for zoom in: it is the same physical key, and unshifted, so the
        // shortcut works without reaching for another modifier.
        Menu menu = new Menu("View");
        menu.getItems().addAll(
                item("Zoom In", shortcut(KeyCode.EQUALS), canvas::zoomIn),
                item("Zoom Out", shortcut(KeyCode.MINUS), canvas::zoomOut),
                item("Actual Size", shortcut(KeyCode.DIGIT0), canvas::resetZoom),
                item("Zoom to Fit", shortcut(KeyCode.DIGIT0, KeyCombination.SHIFT_DOWN), canvas::zoomToFit));
        return menu;
    }

    /**
     * Run-time controls for the graph on the canvas. Just Watch speed for now — it is the one thing
     * the app can change about how a graph runs without editing it.
     */
    private Menu runMenu() {
        Menu watch = new Menu("Watch Speed");
        ToggleGroup group = new ToggleGroup();
        for (WatchSpeed speed : WatchSpeed.values()) {
            RadioMenuItem choice = new RadioMenuItem(speed.label());
            choice.setToggleGroup(group);
            choice.setSelected(speed == WatchSpeed.OFF);
            choice.setOnAction(event -> actions.setStepDelayMillis(speed.millis()));
            watch.getItems().add(choice);
        }

        Menu menu = new Menu("Run");
        menu.getItems().add(watch);
        return menu;
    }

    private Menu toolsMenu() {
        Menu menu = new Menu("Tools");
        menu.getItems().addAll(
                item("Secrets…", null, actions::editSecrets),
                item("Node Libraries…", null, actions::manageNodeLibraries),
                item("Logs…", shortcut(KeyCode.L), actions::showLogs),
                new SeparatorMenuItem(),
                item("Open Data Folder", null, actions::openDataFolder));
        return menu;
    }

    private Menu helpMenu() {
        Menu menu = new Menu("Help");
        menu.getItems().addAll(
                item("Documentation", null, actions::openDocumentation),
                item("Keyboard Shortcuts", null, this::showShortcuts),
                new SeparatorMenuItem(),
                item("About HouseGraph", null, this::showAbout));
        return menu;
    }

    /** The documentation URL Help ▸ Documentation opens; the app supplies the browser. */
    public static String documentationUrl() {
        return DOCUMENTATION_URL;
    }

    private void showShortcuts() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(getScene() == null ? null : getScene().getWindow());
        alert.setTitle("Keyboard Shortcuts");
        alert.setHeaderText("Keyboard and mouse");
        // A Label in the dialog pane rather than contentText, because the listing is two aligned
        // columns and the dialog's default font is proportional — which would ragged them.
        Label listing = new Label("""
                Canvas
                  Right-click            Add a node (search, or browse by category)
                  Middle-drag            Pan
                  Scroll                 Zoom, anchored to the pointer
                  Left-drag on empty     Rubber-band select nodes, edges and waypoints
                  Drag port to port      Data edge; drag corner to corner for a flow edge

                Editing
                  Ctrl/Cmd+Z             Undo
                  Ctrl/Cmd+Shift+Z       Redo
                  Ctrl/Cmd+C / +V        Copy / paste at the pointer
                  Ctrl/Cmd+A             Select all
                  Delete / Backspace     Delete the selection

                File
                  Ctrl/Cmd+N             New graph
                  Ctrl/Cmd+O             Open
                  Ctrl/Cmd+S             Save
                  Ctrl/Cmd+Shift+S       Save as
                  Ctrl/Cmd+E             Export images

                View
                  Ctrl/Cmd+= / +-        Zoom in / out
                  Ctrl/Cmd+0             Actual size
                  Ctrl/Cmd+Shift+0       Zoom to fit""");
        listing.setStyle("-fx-font-family: monospace;");
        alert.getDialogPane().setContent(listing);
        alert.getDialogPane().setMinWidth(560);
        alert.showAndWait();
    }

    private void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(getScene() == null ? null : getScene().getWindow());
        alert.setTitle("About HouseGraph");
        alert.setHeaderText("HouseGraph " + version());
        alert.setContentText("A node-graph editor for home automation.\n\n" + DOCUMENTATION_URL);
        alert.showAndWait();
    }

    /**
     * The build's version from the jar manifest — the same string {@code housegraph --version}
     * prints, read the same way, but derived here rather than borrowed from {@code cli/} so the UI
     * does not depend on the command-line package for one string.
     *
     * @return the implementation version, or a stand-in when running from exploded classes, where
     *         there is no manifest to read one from
     */
    private static String version() {
        String version = MainMenuBar.class.getPackage().getImplementationVersion();
        return version == null ? "(development build)" : version;
    }

    /** A menu item with an optional accelerator; {@code accelerator} may be null. */
    private static MenuItem item(String text, KeyCombination accelerator, Runnable action) {
        MenuItem menuItem = new MenuItem(text);
        if (accelerator != null) {
            menuItem.setAccelerator(accelerator);
        }
        menuItem.setOnAction(event -> action.run());
        return menuItem;
    }

    /** Ctrl on Windows/Linux, Command on macOS — SHORTCUT_DOWN is what makes one binding do both. */
    private static KeyCombination shortcut(KeyCode code, KeyCombination.Modifier... extra) {
        KeyCombination.Modifier[] modifiers = new KeyCombination.Modifier[extra.length + 1];
        modifiers[0] = KeyCombination.SHORTCUT_DOWN;
        System.arraycopy(extra, 0, modifiers, 1, extra.length);
        return new KeyCodeCombination(code, modifiers);
    }

    /**
     * The step delays the Run ▸ Watch Speed menu offers, as {@code NodeGraph.setStepDelayMillis}
     * values. A short list of round numbers rather than a slider: the useful range spans a factor of
     * ten and the exact figure never matters, only whether a run crawls or flies.
     */
    private enum WatchSpeed {
        OFF("Off", 0),
        QUARTER_SECOND("0.25s", 250),
        HALF_SECOND("0.5s", 500),
        ONE_SECOND("1s", 1000),
        TWO_SECONDS("2s", 2000);

        private final String label;
        private final long millis;

        WatchSpeed(String label, long millis) {
            this.label = label;
            this.millis = millis;
        }

        String label() {
            return label;
        }

        long millis() {
            return millis;
        }
    }
}
