package io.github.jaymcole.housegraph.ui.settings;

import io.github.jaymcole.housegraph.logging.LogLevel;
import io.github.jaymcole.housegraph.logging.LogManager;
import io.github.jaymcole.housegraph.logging.LogSink;
import io.github.jaymcole.housegraph.storage.AppDirectories;
import io.github.jaymcole.housegraph.storage.AppPreferences;
import io.github.jaymcole.housegraph.ui.log.ExternalLogDestinations;
import io.github.jaymcole.housegraph.ui.log.ExternalLogSettingsDialog;
import io.github.jaymcole.housegraph.ui.log.LogLevelPreferences;
import io.github.jaymcole.housegraph.ui.menu.WatchSpeed;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The preferences window: one place for everything HouseGraph remembers between launches.
 *
 * <h2>There is no OK button</h2>
 * Every control applies as it is changed — saved to {@link AppPreferences}, pushed onto the
 * running app, and reflected in the open windows before the user looks away. A preferences
 * dialog that batches changes behind OK has to explain which of them needed a restart; one that
 * applies immediately does not, and lets the effect of a choice be seen while the window making
 * it is still open. That is why this is a non-modal top-level window rather than a modal dialog,
 * the same shape as the log window.
 *
 * <p>Two settings are labelled as taking effect at the next launch — reopening the last graph,
 * and restoring the window size. Neither is a plumbing gap: they describe what happens
 * <em>during startup</em>, which has already happened by the time they can be edited.
 *
 * <h2>Where a change goes</h2>
 * {@link AppSettings#applyGlobally()} covers the process-wide half (the graph folder, the log
 * outputs). The {@code onChanged} callback hands the new settings to the host application for the
 * per-window half. Log levels and the external destination are not part of {@link AppSettings} —
 * they have their own stores and their own live-apply paths in {@code ui.log} — so this window
 * drives those through {@link LogLevelPreferences} and {@link ExternalLogDestinations} directly,
 * which is also what keeps them in step with the log window's own controls.
 *
 * <p>A single instance is reused; {@link #show} is a toggle-to-front. Must be called on the FX
 * thread.
 */
public final class SettingsWindow {

    private static SettingsWindow instance;

    private final AppPreferences preferences;
    private final Consumer<AppSettings> onChanged;
    private final Stage stage;

    /**
     * Set while the controls are being populated from saved values, so the listeners that would
     * otherwise commit each programmatic change — writing the file once per control on every
     * open — stay quiet until the user is the one moving something.
     */
    private boolean loading;

    /** The chosen graph folder, or null for {@link AppDirectories#defaultSaves()}. */
    private Path graphFolder;

    private final TextField graphFolderField = new TextField();
    private final CheckBox rememberLastFolder = new CheckBox("Start file dialogs in the folder last used");
    private final CheckBox reopenLastGraph = new CheckBox("Reopen the last graph on launch");
    private final CheckBox restoreWindowSize = new CheckBox("Restore the last window size");
    private final Spinner<Integer> recentFilesCap = new Spinner<>();
    private final ComboBox<WatchSpeed> defaultWatchSpeed = new ComboBox<>();
    private final Spinner<Integer> logFileMaxMegabytes = new Spinner<>();
    private final Spinner<Integer> logFileMaxBackups = new Spinner<>();
    private final Spinner<Integer> logBufferCapacity = new Spinner<>();
    private final CheckBox logAutoScroll = new CheckBox("Follow new records in the log window");
    private final CheckBox warnBeforeInstall = new CheckBox("Warn before installing a node library");

    /** Rebuilt on every open: an external destination can be added or removed between them. */
    private final VBox outputLevels = new VBox(6);

    private final Label status = new Label();

    /**
     * Opens the preferences window, creating it on first use and bringing the existing one to the
     * front thereafter.
     *
     * @param preferences the shared store settings are read from and written to
     * @param onChanged   handed the new settings after every change, for the per-window half of
     *                    applying them
     */
    public static void show(AppPreferences preferences, Consumer<AppSettings> onChanged) {
        if (instance == null) {
            instance = new SettingsWindow(preferences, onChanged);
        }
        instance.open();
    }

    private SettingsWindow(AppPreferences preferences, Consumer<AppSettings> onChanged) {
        this.preferences = preferences;
        this.onChanged = onChanged;
        stage = new Stage();
        stage.setTitle("HouseGraph Settings");
        stage.setScene(new Scene(buildRoot(), 620, 560));
    }

    private void open() {
        if (stage.isShowing()) {
            stage.toFront();
            return;
        }
        populate(AppSettings.load(preferences));
        stage.show();
        stage.toFront();
    }

    // --- Layout ---------------------------------------------------------------------

    private BorderPane buildRoot() {
        TabPane tabs = new TabPane(
                tab("General", generalPane()),
                tab("Editor", editorPane()),
                tab("Logging", loggingPane()),
                tab("Node Libraries", librariesPane()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        status.setWrapText(true);
        status.setStyle("-fx-text-fill: #6c757d;");

        Button restoreDefaults = new Button("Restore Defaults");
        restoreDefaults.setOnAction(e -> {
            graphFolder = null;
            populate(AppSettings.defaults());
            commit();
            status.setText("Every setting is back to its default.");
        });

        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());

        HBox actions = new HBox(8, restoreDefaults, spacer(), close);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(10, 14, 4, 14));

        VBox footer = new VBox(4, actions, paddedStatus());

        BorderPane root = new BorderPane();
        root.setCenter(tabs);
        root.setBottom(footer);
        return root;
    }

    private Node paddedStatus() {
        VBox box = new VBox(status);
        box.setPadding(new Insets(0, 14, 10, 14));
        return box;
    }

    private Tab tab(String title, Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        return new Tab(title, scroll);
    }

    private Node generalPane() {
        graphFolderField.setEditable(false);
        graphFolderField.setPrefWidth(320);
        HBox.setHgrow(graphFolderField, Priority.ALWAYS);

        Button browse = new Button("Choose…");
        browse.setOnAction(e -> chooseGraphFolder());

        Button useDefault = new Button("Use Default");
        useDefault.setOnAction(e -> {
            graphFolder = null;
            showGraphFolder();
            commit();
        });

        HBox folderRow = new HBox(6, graphFolderField, browse, useDefault);
        folderRow.setAlignment(Pos.CENTER_LEFT);

        rememberLastFolder.selectedProperty().addListener((obs, was, now) -> commit());
        reopenLastGraph.selectedProperty().addListener((obs, was, now) -> commit());
        restoreWindowSize.selectedProperty().addListener((obs, was, now) -> commit());
        configureSpinner(recentFilesCap, 1, 50, 1);

        return section(
                heading("Graphs"),
                new Label("Graph folder"),
                folderRow,
                hint("Where Open and Save start, and the only HouseGraph directory you can move."
                        + " Secrets, node libraries and logs stay under the app's own folder."),
                rememberLastFolder,
                new Separator(),
                heading("Recent graphs"),
                labelled("Entries to keep", recentFilesCap),
                new Separator(),
                heading("Startup"),
                reopenLastGraph,
                restoreWindowSize,
                hint("Both describe what happens while the app is starting, so they take effect"
                        + " at the next launch."));
    }

    private Node editorPane() {
        defaultWatchSpeed.getItems().setAll(WatchSpeed.values());
        defaultWatchSpeed.setConverter(new StringConverter<>() {
            @Override
            public String toString(WatchSpeed speed) {
                return speed == null ? "" : speed.label();
            }

            @Override
            public WatchSpeed fromString(String text) {
                return null;
            }
        });
        defaultWatchSpeed.valueProperty().addListener((obs, was, now) -> commit());

        return section(
                heading("Watch speed"),
                labelled("New windows start at", defaultWatchSpeed),
                hint("The pause the engine takes between flow steps, so a run can be followed by"
                        + " eye. Changing this applies to every open window as well; Run ▸ Watch"
                        + " Speed still overrides it for one window."));
    }

    private Node loggingPane() {
        configureSpinner(logFileMaxMegabytes, 1, 1024, 1);
        configureSpinner(logFileMaxBackups, 0, 50, 1);
        configureSpinner(logBufferCapacity, 100, 200_000, 500);
        logAutoScroll.selectedProperty().addListener((obs, was, now) -> commit());

        Button external = new Button("External Destination…");
        external.setOnAction(e -> ExternalLogSettingsDialog.show(stage, preferences, () -> {
            showOutputLevels();
            onChanged.accept(AppSettings.load(preferences));
        }));

        return section(
                heading("Output levels"),
                outputLevels,
                hint("The same per-output levels the log window's toolbar offers; changing either"
                        + " moves the other."),
                external,
                new Separator(),
                heading("Log file"),
                labelled("Roll over at (MB)", logFileMaxMegabytes),
                labelled("Generations to keep", logFileMaxBackups),
                hint("Lowering the threshold below the file's current size rolls it immediately."),
                new Separator(),
                heading("Log window"),
                labelled("Records to retain", logBufferCapacity),
                logAutoScroll,
                hint("Lowering the retained count discards the oldest records straight away."));
    }

    private Node librariesPane() {
        warnBeforeInstall.selectedProperty().addListener((obs, was, now) -> commit());

        return section(
                heading("Installing"),
                warnBeforeInstall,
                hint("A node library runs with the same privileges as HouseGraph itself. The"
                        + " warning shown before one is downloaded has a “don't show this"
                        + " again” box; this is where it is switched back on."),
                new Separator(),
                heading("Unattended installs"),
                hint("What a headless daemon may fetch and run is configured by hand in"
                        + " config/remote.json, not here. That file is the trust boundary: a"
                        + " checkbox that widened it would defeat the point of having one."));
    }

    // --- Populating and committing -----------------------------------------------------

    private void populate(AppSettings settings) {
        loading = true;
        try {
            graphFolder = settings.graphFolder();
            showGraphFolder();
            rememberLastFolder.setSelected(settings.rememberLastFolder());
            reopenLastGraph.setSelected(settings.reopenLastGraph());
            restoreWindowSize.setSelected(settings.restoreWindowSize());
            recentFilesCap.getValueFactory().setValue(settings.recentFilesCap());
            // An exactly-matching speed or nothing: a hand-edited delay that is not on the list is
            // still in force, and showing the nearest entry would misreport it as chosen.
            defaultWatchSpeed.setValue(WatchSpeed.forMillis(settings.defaultStepDelayMillis()).orElse(null));
            logFileMaxMegabytes.getValueFactory().setValue(toMegabytes(settings.logFileMaxBytes()));
            logFileMaxBackups.getValueFactory().setValue(settings.logFileMaxBackups());
            logBufferCapacity.getValueFactory().setValue(settings.logBufferCapacity());
            logAutoScroll.setSelected(settings.logAutoScroll());
            warnBeforeInstall.setSelected(!settings.skipInstallWarning());
            showOutputLevels();
        } finally {
            loading = false;
        }
    }

    /** The settings as the controls currently read. */
    private AppSettings current() {
        WatchSpeed speed = defaultWatchSpeed.getValue();
        return new AppSettings(
                graphFolder,
                rememberLastFolder.isSelected(),
                reopenLastGraph.isSelected(),
                restoreWindowSize.isSelected(),
                recentFilesCap.getValue(),
                speed == null ? 0 : speed.millis(),
                logFileMaxMegabytes.getValue() * 1024L * 1024L,
                logFileMaxBackups.getValue(),
                logBufferCapacity.getValue(),
                logAutoScroll.isSelected(),
                !warnBeforeInstall.isSelected());
    }

    /**
     * Saves what the controls say and applies it — to the process here, and to the windows through
     * {@link #onChanged}. Called by every control's own listener, which is what makes a change take
     * effect as it is made rather than when the window is dismissed.
     */
    private void commit() {
        if (loading) {
            return;
        }
        AppSettings settings = current();
        settings.save(preferences);
        settings.applyGlobally();
        onChanged.accept(settings);
        status.setText("Saved.");
    }

    private void chooseGraphFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose Graph Folder");
        File initial = (graphFolder == null ? AppDirectories.get().saves() : graphFolder).toFile();
        if (initial.isDirectory()) {
            chooser.setInitialDirectory(initial);
        }
        File chosen = chooser.showDialog(stage);
        if (chosen == null) {
            return;
        }
        Path candidate = chosen.toPath().toAbsolutePath().normalize();
        // Checked before it is stored, so an unusable folder is reported while the user is still
        // thinking about folders rather than at their next save.
        String problem = AppSettings.graphFolderProblem(candidate);
        if (problem != null) {
            status.setText(problem);
            return;
        }
        graphFolder = candidate;
        showGraphFolder();
        commit();
    }

    private void showGraphFolder() {
        Path effective = graphFolder == null ? AppDirectories.get().defaultSaves() : graphFolder;
        graphFolderField.setText(effective.toString());
        graphFolderField.setTooltip(new Tooltip(
                graphFolder == null ? "The built-in default" : "A folder you chose"));
    }

    /**
     * Rebuilds the per-output level rows from the registered sinks, and wires each to the same
     * {@link LogLevelPreferences} path the log window uses — so a level set here is the level set
     * there, live on the sink and saved under the same key.
     */
    private void showOutputLevels() {
        outputLevels.getChildren().clear();
        for (LogSink sink : LogManager.get().sinks()) {
            ComboBox<LogLevel> combo = new ComboBox<>();
            combo.getItems().setAll(LogLevel.values());
            combo.getSelectionModel().select(sink.getLevel());
            combo.valueProperty().addListener((obs, was, level) -> {
                if (level == null) {
                    return;
                }
                sink.setLevel(level);
                LogLevelPreferences.persist(preferences, sink);
                status.setText("Saved.");
            });
            outputLevels.getChildren().add(labelled(sink.name(), combo));
        }
    }

    // --- Small builders -----------------------------------------------------------------

    private VBox section(Node... children) {
        VBox box = new VBox(10, children);
        box.setPadding(new Insets(14));
        return box;
    }

    private Label heading(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: #6c757d;");
        return label;
    }

    private HBox labelled(String text, Node control) {
        Label label = new Label(text);
        label.setMinWidth(150);
        HBox row = new HBox(8, label, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    /**
     * Sets a spinner's range and makes it commit what was typed.
     *
     * <h4>Why the focus listener</h4>
     * An editable {@link Spinner} does not push typed text into its value until Enter is pressed,
     * so a user who types a number and clicks another control would have their entry silently
     * discarded — which in a window with no OK button means the change is simply lost. Committing
     * on focus loss closes that gap; a value the editor cannot parse reverts to the last good one.
     */
    private void configureSpinner(Spinner<Integer> spinner, int minimum, int maximum, int step) {
        spinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(minimum, maximum, minimum, step));
        spinner.setEditable(true);
        spinner.setPrefWidth(120);
        spinner.valueProperty().addListener((obs, was, now) -> commit());
        spinner.focusedProperty().addListener((obs, was, focused) -> {
            if (focused) {
                return;
            }
            try {
                int typed = Integer.parseInt(spinner.getEditor().getText().trim());
                spinner.getValueFactory().setValue(Math.min(maximum, Math.max(minimum, typed)));
            } catch (NumberFormatException e) {
                spinner.getEditor().setText(String.valueOf(spinner.getValue()));
            }
        });
    }

    /** Bytes as whole megabytes, never rounding a configured size down to zero. */
    private static int toMegabytes(long bytes) {
        return (int) Math.max(1, bytes / (1024 * 1024));
    }
}
