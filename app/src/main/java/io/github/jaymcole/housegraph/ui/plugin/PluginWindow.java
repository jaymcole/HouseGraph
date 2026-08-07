package io.github.jaymcole.housegraph.ui.plugin;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.plugin.GitHubReleases;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.plugin.PluginInstaller;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Manage installed node libraries: add one from a GitHub URL, check for updates, remove, or
 * enable/disable.
 *
 * <p>Modelled on {@code LogWindow} rather than {@code SecretsEditor}: a non-modal, unowned,
 * singleton stage that toggles to front. Installing is a long, network-bound operation and the user
 * should be able to look at the canvas and the log window while it runs — a modal dialog would
 * forbid exactly that. Structurally it is a table of rows with per-row status, which is
 * {@code LogWindow}'s shape; {@code SecretsEditor}'s list-plus-form is for editing one item.
 *
 * <p>Deliberately a thin shell. Everything worth testing lives in the headless
 * {@code plugin} package, because this project has no infrastructure for testing JavaFX windows.
 */
public final class PluginWindow {

    private static final Logger log = Log.get(PluginWindow.class);

    private static PluginWindow instance;

    private final PluginCatalog catalog;
    private final Runnable onLibrariesChanged;
    private final java.util.function.ToIntFunction<String> liveNodeCount;
    private final Stage stage;
    private final TableView<Row> table = new TableView<>();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final Label status = new Label();

    /** Latest known release per library id, from the last explicit update check. */
    private final Map<String, GitHubReleases.Release> latestKnown = new HashMap<>();

    /** One table row. A view model, so the table never reaches into the catalog mid-render. */
    public static final class Row {
        private final PluginCatalog.Installed installed;
        private final SimpleStringProperty latest = new SimpleStringProperty("—");
        private final SimpleStringProperty state = new SimpleStringProperty("");

        Row(PluginCatalog.Installed installed) {
            this.installed = installed;
        }

        public String getId() {
            return installed.id();
        }

        public String getName() {
            return installed.name();
        }

        public String getVersion() {
            return installed.version();
        }

        public String getApiVersion() {
            return installed.apiVersion() == null ? "—" : installed.apiVersion();
        }

        public String getEnabled() {
            return installed.enabled() ? "yes" : "no";
        }

        public SimpleStringProperty latestProperty() {
            return latest;
        }

        public SimpleStringProperty stateProperty() {
            return state;
        }
    }

    /**
     * Opens the window, or brings it to the front if already open.
     *
     * @param catalog            the installed libraries
     * @param onLibrariesChanged called on the FX thread after a change that needs the node registry
     *                           and Add-Node menu rebuilt
     * @param liveNodeCount      how many nodes from a given library are on the canvas right now;
     *                           a change affecting live nodes needs a restart, not a hot reload
     */
    public static void show(PluginCatalog catalog, Runnable onLibrariesChanged,
                            java.util.function.ToIntFunction<String> liveNodeCount) {
        if (instance == null) {
            instance = new PluginWindow(catalog, onLibrariesChanged, liveNodeCount);
        }
        instance.open();
    }

    /**
     * Opens the window and immediately begins installing from {@code repositoryUrl}, behind the
     * usual per-repository confirmation. Used by the missing-library prompt on load, so the user
     * doesn't have to copy a URL out of a dialog and paste it back in.
     *
     * @param repositoryUrl the repository a save file recorded for a library it needs
     */
    public static void showAndInstall(PluginCatalog catalog, Runnable onLibrariesChanged,
                                      java.util.function.ToIntFunction<String> liveNodeCount,
                                      String repositoryUrl) {
        show(catalog, onLibrariesChanged, liveNodeCount);
        instance.installFrom(repositoryUrl);
    }

    private PluginWindow(PluginCatalog catalog, Runnable onLibrariesChanged,
                         java.util.function.ToIntFunction<String> liveNodeCount) {
        this.catalog = catalog;
        this.onLibrariesChanged = onLibrariesChanged;
        this.liveNodeCount = liveNodeCount;
        stage = new Stage();
        stage.setTitle("HouseGraph Node Libraries");
        stage.setScene(new Scene(buildRoot(), 860, 420));
    }

    private void open() {
        if (stage.isShowing()) {
            stage.toFront();
            return;
        }
        refresh();
        stage.show();
        stage.toFront();
    }

    private BorderPane buildRoot() {
        BorderPane root = new BorderPane();
        root.setTop(buildToolBar());
        root.setCenter(buildTable());
        status.setPadding(new Insets(6, 10, 6, 10));
        status.setWrapText(true);
        root.setBottom(status);
        return root;
    }

    private ToolBar buildToolBar() {
        Button add = new Button("Add from URL…");
        add.setOnAction(e -> promptAndInstall());

        Button check = new Button("Check for Updates");
        // Never automatic: unauthenticated GitHub allows 60 requests an hour per IP, and checking
        // every library costs one each.
        check.setOnAction(e -> checkForUpdates());

        Button update = new Button("Update");
        update.setOnAction(e -> updateSelected());

        Button toggle = new Button("Enable/Disable");
        toggle.setOnAction(e -> toggleSelected());

        Button remove = new Button("Remove");
        remove.setOnAction(e -> removeSelected());

        return new ToolBar(add, check, update, toggle, remove);
    }

    private TableView<Row> buildTable() {
        table.setItems(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().setAll(
                column("Id", 170, row -> new SimpleStringProperty(row.getId())),
                column("Name", 150, row -> new SimpleStringProperty(row.getName())),
                column("Installed", 90, row -> new SimpleStringProperty(row.getVersion())),
                column("Latest", 90, Row::latestProperty),
                column("API", 60, row -> new SimpleStringProperty(row.getApiVersion())),
                column("Enabled", 70, row -> new SimpleStringProperty(row.getEnabled())),
                column("Status", 200, Row::stateProperty));
        table.setPlaceholder(new Label("No node libraries installed. Use “Add from URL…” "
                + "with a library's GitHub repository."));
        return table;
    }

    private static TableColumn<Row, String> column(String title, double width,
                                                   java.util.function.Function<Row, javafx.beans.value.ObservableValue<String>> value) {
        TableColumn<Row, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(data -> value.apply(data.getValue()));
        return col;
    }

    private void refresh() {
        rows.setAll(catalog.all().stream().map(Row::new).toList());
        for (Row row : rows) {
            GitHubReleases.Release known = latestKnown.get(row.getId());
            if (known != null) {
                row.latestProperty().set(known.version());
                row.stateProperty().set(known.version().equals(row.getVersion()) ? "Up to date" : "Update available");
            }
            java.nio.file.Path jar = catalog.jarFor(catalog.byId(row.getId()).orElseThrow());
            if (!java.nio.file.Files.isRegularFile(jar)) {
                row.stateProperty().set("Jar missing — reinstall");
            }
        }
        table.refresh();
    }

    // --- Actions --------------------------------------------------------------------------------

    private void promptAndInstall() {
        TextInputDialog dialog = new TextInputDialog("https://github.com/");
        dialog.initOwner(stage);
        dialog.setTitle("Add a node library");
        dialog.setHeaderText("Paste the library's GitHub repository URL.");
        dialog.setContentText("Repository:");
        Optional<String> url = dialog.showAndWait();
        url.filter(value -> !value.isBlank()).ifPresent(this::installFrom);
    }

    /**
     * Installs from a repository URL after an explicit confirmation naming what is about to be
     * downloaded and run.
     */
    public void installFrom(String repositoryUrl) {
        runOffThread("Looking up the latest release of " + repositoryUrl + "…", () -> {
            GitHubReleases.Release release = GitHubReleases.latest(repositoryUrl, null)
                    .orElseThrow(() -> new IllegalStateException("No release information returned"));
            Platform.runLater(() -> confirmThenInstall(repositoryUrl, release));
        });
    }

    private void confirmThenInstall(String repositoryUrl, GitHubReleases.Release release) {
        // Trust-on-first-use, stated plainly. This matters most when a save file proposed the
        // repository: that is untrusted input asking to download and execute code, so it must never
        // install silently. And there is no sandbox to fall back on.
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(stage);
        confirm.setTitle("Install node library");
        confirm.setHeaderText("Install " + release.asset().name() + " from " + repositoryUrl + "?");
        confirm.setContentText("Release " + release.tagName() + " — "
                + formatSize(release.asset().sizeBytes()) + ".\n\n"
                + "A node library runs with the same access as HouseGraph itself: your files, your "
                + "network, and your saved secrets. Install it only if you trust its author, exactly "
                + "as you would any program you downloaded.");
        confirm.getDialogPane().setMinWidth(520);
        if (confirm.showAndWait().filter(button -> button.getButtonData().isDefaultButton()).isEmpty()) {
            status.setText("Install cancelled.");
            return;
        }

        runOffThread("Downloading " + release.asset().name() + "…", () -> {
            PluginInstaller.install(repositoryUrl, release, catalog);
        }, () -> {
            latestKnown.put(catalogIdOf(repositoryUrl), release);
            librariesChanged("Installed " + release.asset().name() + ".");
        });
    }

    private String catalogIdOf(String repositoryUrl) {
        return catalog.all().stream()
                .filter(installed -> repositoryUrl.equals(installed.repository()))
                .map(PluginCatalog.Installed::id)
                .findFirst()
                .orElse("");
    }

    private void checkForUpdates() {
        if (catalog.all().isEmpty()) {
            status.setText("Nothing installed yet.");
            return;
        }
        runOffThread("Checking for updates…", () -> {
            for (PluginCatalog.Installed installed : catalog.all()) {
                if (installed.repository() == null) {
                    continue;
                }
                GitHubReleases.latest(installed.repository(), null)
                        .ifPresent(release -> latestKnown.put(installed.id(), release));
            }
        }, () -> {
            refresh();
            status.setText("Update check complete.");
        });
    }

    private void updateSelected() {
        selected().ifPresent(row -> {
            PluginCatalog.Installed installed = catalog.byId(row.getId()).orElseThrow();
            if (installed.repository() == null) {
                status.setText("\"" + row.getId() + "\" has no recorded repository to update from.");
                return;
            }
            if (requiresRestart(row.getId(), "updated")) {
                return;
            }
            installFrom(installed.repository());
        });
    }

    private void toggleSelected() {
        selected().ifPresent(row -> {
            PluginCatalog.Installed installed = catalog.byId(row.getId()).orElseThrow();
            boolean enabling = !installed.enabled();
            if (!enabling && requiresRestart(row.getId(), "disabled")) {
                return;
            }
            catalog.setEnabled(row.getId(), enabling);
            catalog.save();
            librariesChanged((enabling ? "Enabled " : "Disabled ") + row.getId() + ".");
        });
    }

    private void removeSelected() {
        selected().ifPresent(row -> {
            if (requiresRestart(row.getId(), "removed")) {
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.initOwner(stage);
            confirm.setHeaderText("Remove \"" + row.getId() + "\"?");
            confirm.setContentText("Graphs using its nodes will still open — those nodes are kept as "
                    + "placeholders and come back if you reinstall it.");
            if (confirm.showAndWait().filter(button -> button.getButtonData().isDefaultButton()).isEmpty()) {
                return;
            }
            catalog.remove(row.getId());
            catalog.save();
            librariesChanged("Removed " + row.getId() + ". Its jar is cleaned up at next startup.");
        });
    }

    /**
     * Blocks a change that can't be applied while nodes from that library are on the canvas, and
     * says so plainly. You cannot unload a class while instances exist: those nodes stay bound to
     * the old loader's {@code Class} objects, so the same type would exist twice and
     * {@code NodeRegistry.duplicate} would clone the stale one.
     *
     * @return true if the caller should stop
     */
    private boolean requiresRestart(String pluginId, String verb) {
        int live = liveNodeCount.applyAsInt(pluginId);
        if (live == 0) {
            return false;
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setHeaderText("\"" + pluginId + "\" can't be " + verb + " while it's in use");
        alert.setContentText(live + " node" + (live == 1 ? "" : "s") + " in the open graph come"
                + (live == 1 ? "s" : "") + " from this library. Java can't unload a class while "
                + "instances of it exist, so this takes effect after a restart. Remove those nodes "
                + "first, or restart HouseGraph.");
        alert.getDialogPane().setMinWidth(460);
        alert.showAndWait();
        status.setText("\"" + pluginId + "\" is in use by " + live + " node(s); restart to change it.");
        return true;
    }

    private Optional<Row> selected() {
        Row row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            status.setText("Select a library first.");
            return Optional.empty();
        }
        return Optional.of(row);
    }

    private void librariesChanged(String message) {
        onLibrariesChanged.run();
        refresh();
        status.setText(message);
    }

    // --- Off-thread plumbing ---------------------------------------------------------------------

    private void runOffThread(String startedMessage, Callable work) {
        runOffThread(startedMessage, work, this::refresh);
    }

    /** Network work never runs on the FX thread; failures surface as an alert, as elsewhere in the UI. */
    private void runOffThread(String startedMessage, Callable work, Runnable onSuccess) {
        status.setText(startedMessage);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                work.call();
                return null;
            }
        };
        task.setOnSucceeded(e -> onSuccess.run());
        task.setOnFailed(e -> {
            Throwable failure = task.getException();
            log.error("Node-library operation failed", failure);
            status.setText("Failed: " + failure.getMessage());
            Alert alert = new Alert(Alert.AlertType.ERROR, failure.getMessage());
            alert.initOwner(stage);
            alert.showAndWait();
        });
        Thread thread = new Thread(task, "housegraph-plugin-op");
        thread.setDaemon(true);
        thread.start();
    }

    private interface Callable {
        void call() throws Exception;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "unknown size";
        }
        if (bytes < 1024 * 1024) {
            return Math.max(1, bytes / 1024) + " KB";
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
