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
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * <p>The table allows multi-selection, and Update/Enable-Disable/Remove act on the whole
 * selection at once (each library that's individually blocked — in use on the canvas — is
 * skipped with one summary alert rather than aborting the batch). Check for Updates checks the
 * selection when one exists, or every installed library when nothing is selected.
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
        // every library costs one each. Checks the selection if any rows are selected, else everything.
        check.setOnAction(e -> checkForUpdates());

        Button update = new Button("Update");
        update.setOnAction(e -> updateSelected());

        Button toggle = new Button("Enable/Disable");
        toggle.setOnAction(e -> toggleSelected());

        Button remove = new Button("Remove");
        remove.setOnAction(e -> removeSelected());

        // Every action above operates on the full selection (TableView.SelectionMode.MULTIPLE),
        // so ctrl/shift-click for bulk enable/disable, update, or remove.

        return new ToolBar(add, check, update, toggle, remove);
    }

    private TableView<Row> buildTable() {
        table.setItems(rows);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
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
        installFrom(repositoryUrl, null);
    }

    /**
     * @param wantedPluginId the library to take from the release when the repository publishes
     *                       several (an update knows which); null to ask
     */
    public void installFrom(String repositoryUrl, String wantedPluginId) {
        runOffThread("Looking up the latest release of " + repositoryUrl + "…", () -> {
            GitHubReleases.Release release = GitHubReleases.latest(repositoryUrl, null)
                    .orElseThrow(() -> new IllegalStateException("No release information returned"));
            Platform.runLater(() -> chooseAssetThenInstall(repositoryUrl, release, wantedPluginId));
        });
    }

    /**
     * Chooses which library to install from a release, asking when the repository publishes more
     * than one — a monorepo attaches a jar per library, and installing whichever came first would
     * silently be the wrong one.
     *
     * @param wantedPluginId the library already known to be wanted (an update), or null to ask
     */
    private void chooseAssetThenInstall(String repositoryUrl, GitHubReleases.Release release, String wantedPluginId) {
        if (wantedPluginId != null) {
            release.assetFor(wantedPluginId).ifPresentOrElse(
                    asset -> confirmThenInstall(repositoryUrl, release, asset),
                    () -> status.setText("Release " + release.tagName() + " has no jar for \""
                            + wantedPluginId + "\"."));
            return;
        }
        if (!release.hasSeveralLibraries()) {
            confirmThenInstall(repositoryUrl, release, release.assets().get(0));
            return;
        }
        buildAssetPicker(release).showAndWait()
                .ifPresent(asset -> confirmThenInstall(repositoryUrl, release, asset));
    }

    /**
     * A dropdown of jar names only — an {@link GitHubReleases.Asset}'s default {@code toString()}
     * dumps every field, which reads as noise when all the user needs to recognize is the name. The
     * size is shown separately, below the dropdown, once something is selected.
     */
    private Dialog<GitHubReleases.Asset> buildAssetPicker(GitHubReleases.Release release) {
        Dialog<GitHubReleases.Asset> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Choose a node library");
        dialog.setHeaderText("Release " + release.tagName() + " publishes "
                + release.assets().size() + " node libraries.");

        ComboBox<GitHubReleases.Asset> combo =
                new ComboBox<>(FXCollections.observableArrayList(release.assets()));
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(GitHubReleases.Asset asset) {
                return asset == null ? "" : asset.name();
            }

            @Override
            public GitHubReleases.Asset fromString(String string) {
                throw new UnsupportedOperationException("not editable");
            }
        });
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.getSelectionModel().selectFirst();

        Label details = new Label();
        details.setWrapText(true);
        combo.valueProperty().addListener((obs, old, asset) ->
                details.setText(asset == null ? "" : formatSize(asset.sizeBytes())));
        details.setText(formatSize(combo.getValue().sizeBytes()));

        VBox content = new VBox(8, new Label("Install:"), combo, details);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button ->
                button != null && button.getButtonData().isDefaultButton() ? combo.getValue() : null);
        return dialog;
    }

    private void confirmThenInstall(String repositoryUrl, GitHubReleases.Release release,
                                    GitHubReleases.Asset asset) {
        // Trust-on-first-use, stated plainly. This matters most when a save file proposed the
        // repository: that is untrusted input asking to download and execute code, so it must never
        // install silently. And there is no sandbox to fall back on.
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(stage);
        confirm.setTitle("Install node library");
        confirm.setHeaderText("Install " + asset.name() + " from " + repositoryUrl + "?");
        confirm.setContentText("Release " + release.tagName() + " — "
                + formatSize(asset.sizeBytes()) + ".\n\n"
                + "A node library runs with the same access as HouseGraph itself: your files, your "
                + "network, and your saved secrets. Install it only if you trust its author, exactly "
                + "as you would any program you downloaded.");
        confirm.getDialogPane().setMinWidth(520);
        if (confirm.showAndWait().filter(button -> button.getButtonData().isDefaultButton()).isEmpty()) {
            status.setText("Install cancelled.");
            return;
        }

        runOffThread("Downloading " + asset.name() + "…", () -> {
            PluginInstaller.install(repositoryUrl, release, asset, catalog);
        }, () -> {
            latestKnown.put(catalogIdOf(repositoryUrl), release);
            librariesChanged("Installed " + asset.name() + ".");
        });
    }

    private String catalogIdOf(String repositoryUrl) {
        return catalog.all().stream()
                .filter(installed -> repositoryUrl.equals(installed.repository()))
                .map(PluginCatalog.Installed::id)
                .findFirst()
                .orElse("");
    }

    /** With no selection, every installed library; otherwise just the selected rows. */
    private void checkForUpdates() {
        List<Row> selected = new ArrayList<>(table.getSelectionModel().getSelectedItems());
        List<PluginCatalog.Installed> targets = selected.isEmpty()
                ? catalog.all()
                : selected.stream().map(row -> catalog.byId(row.getId()).orElseThrow()).toList();
        if (targets.isEmpty()) {
            status.setText("Nothing installed yet.");
            return;
        }
        String verb = selected.isEmpty() ? "Checking for updates…"
                : "Checking for updates on " + targets.size() + " selected…";
        runOffThread(verb, () -> {
            for (PluginCatalog.Installed installed : targets) {
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
        List<Row> selected = selectedRows();
        if (selected.isEmpty()) {
            return;
        }
        List<String> blocked = new ArrayList<>();
        List<String> noRepository = new ArrayList<>();
        List<PluginCatalog.Installed> toUpdate = new ArrayList<>();
        for (Row row : selected) {
            if (liveNodeCount.applyAsInt(row.getId()) > 0) {
                blocked.add(row.getId());
                continue;
            }
            PluginCatalog.Installed installed = catalog.byId(row.getId()).orElseThrow();
            if (installed.repository() == null) {
                noRepository.add(row.getId());
                continue;
            }
            toUpdate.add(installed);
        }
        warnBlocked(blocked, "updated");
        if (!noRepository.isEmpty()) {
            status.setText("No recorded repository to update from: " + String.join(", ", noRepository) + ".");
        }
        // Pass the library id with each: a monorepo's release carries a jar per library, and an
        // update must take the one it already has rather than asking again. Each still goes through
        // its own confirmation dialog before downloading.
        for (PluginCatalog.Installed installed : toUpdate) {
            installFrom(installed.repository(), installed.id());
        }
    }

    private void toggleSelected() {
        List<Row> selected = selectedRows();
        if (selected.isEmpty()) {
            return;
        }
        List<String> blocked = new ArrayList<>();
        int enabledCount = 0;
        int disabledCount = 0;
        for (Row row : selected) {
            PluginCatalog.Installed installed = catalog.byId(row.getId()).orElseThrow();
            boolean enabling = !installed.enabled();
            if (!enabling && liveNodeCount.applyAsInt(row.getId()) > 0) {
                blocked.add(row.getId());
                continue;
            }
            catalog.setEnabled(row.getId(), enabling);
            if (enabling) {
                enabledCount++;
            } else {
                disabledCount++;
            }
        }
        warnBlocked(blocked, "disabled");
        if (enabledCount == 0 && disabledCount == 0) {
            return;
        }
        catalog.save();
        StringBuilder message = new StringBuilder();
        if (enabledCount > 0) {
            message.append("Enabled ").append(enabledCount).append(enabledCount == 1 ? " library. " : " libraries. ");
        }
        if (disabledCount > 0) {
            message.append("Disabled ").append(disabledCount).append(disabledCount == 1 ? " library." : " libraries.");
        }
        librariesChanged(message.toString().trim());
    }

    private void removeSelected() {
        List<Row> selected = selectedRows();
        if (selected.isEmpty()) {
            return;
        }
        List<String> blocked = new ArrayList<>();
        List<Row> removable = new ArrayList<>();
        for (Row row : selected) {
            if (liveNodeCount.applyAsInt(row.getId()) > 0) {
                blocked.add(row.getId());
            } else {
                removable.add(row);
            }
        }
        warnBlocked(blocked, "removed");
        if (removable.isEmpty()) {
            return;
        }

        boolean plural = removable.size() > 1;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(stage);
        confirm.setHeaderText(plural ? "Remove " + removable.size() + " libraries?"
                : "Remove \"" + removable.get(0).getId() + "\"?");
        confirm.setContentText("Graphs using " + (plural ? "their nodes" : "its nodes") + " will still open "
                + "— those nodes are kept as placeholders and come back if you reinstall "
                + (plural ? "them" : "it") + ".");
        if (confirm.showAndWait().filter(button -> button.getButtonData().isDefaultButton()).isEmpty()) {
            return;
        }
        for (Row row : removable) {
            catalog.remove(row.getId());
        }
        catalog.save();
        librariesChanged("Removed " + removable.size() + (plural ? " libraries. Their jars are"
                : " library. Its jar is") + " cleaned up at next startup.");
    }

    /**
     * Shows one summary alert for every library a bulk action skipped because its nodes are on the
     * canvas. You cannot unload a class while instances exist: those nodes stay bound to the old
     * loader's {@code Class} objects, so the same type would exist twice and
     * {@code NodeRegistry.duplicate} would clone the stale one. A no-op when nothing was blocked.
     */
    private void warnBlocked(List<String> blockedIds, String verb) {
        if (blockedIds.isEmpty()) {
            return;
        }
        boolean plural = blockedIds.size() > 1;
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setHeaderText((plural ? blockedIds.size() + " libraries can't be " + verb
                : "\"" + blockedIds.get(0) + "\" can't be " + verb) + " while in use");
        alert.setContentText("Java can't unload a class while instances of it exist, so this takes "
                + "effect after a restart. Remove those nodes from the canvas first, or restart "
                + "HouseGraph.\n\n" + String.join(", ", blockedIds));
        alert.getDialogPane().setMinWidth(460);
        alert.showAndWait();
        status.setText((plural ? blockedIds.size() + " libraries are" : "\"" + blockedIds.get(0) + "\" is")
                + " in use; restart to change " + (plural ? "them" : "it") + ".");
    }

    private List<Row> selectedRows() {
        List<Row> selected = new ArrayList<>(table.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            status.setText("Select at least one library first.");
        }
        return selected;
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
