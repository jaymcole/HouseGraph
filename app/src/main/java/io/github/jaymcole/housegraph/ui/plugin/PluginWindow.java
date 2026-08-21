package io.github.jaymcole.housegraph.ui.plugin;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.plugin.GitHubReleases;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.plugin.PluginInstaller;
import io.github.jaymcole.housegraph.storage.AppPreferences;
import io.github.jaymcole.housegraph.ui.widget.TaskProgressBar;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manage installed node libraries: add one or more from a GitHub URL, check for updates, remove, or
 * enable/disable.
 *
 * <p>Modelled on {@code LogWindow} rather than {@code SecretsEditor}: a non-modal, unowned,
 * singleton stage that toggles to front. Installing is a long, network-bound operation and the user
 * should be able to look at the canvas and the log window while it runs — a modal dialog would
 * forbid exactly that. Structurally it is a table of rows with per-row status, which is
 * {@code LogWindow}'s shape; {@code SecretsEditor}'s list-plus-form is for editing one item.
 *
 * <p>The table allows multi-selection, and every action operates on the whole selection at once
 * rather than one item at a time. Add from URL ({@link AddFromUrlDialog}) shows every package a
 * repository's latest release publishes as a table with an Add button per row, instead of asking
 * the user to pick exactly one from a dropdown. Update and Remove each show a single confirmation
 * summarising the whole batch — one dialog naming every affected library, not one dialog per
 * library — so accepting once acts on all of them. The catalog/disk change always happens
 * immediately — the version-stamped jar path and plain JSON catalog write are safe even while a
 * library's nodes are live. What defers is the in-memory hot reload: rebuilding the shared class
 * loader would leave any node-library node currently on the canvas bound to a discarded
 * {@code Class}, so a reload only runs when the canvas has none. When it can't, the change is left
 * pending and takes effect on the next restart; the first time that happens in a session gets one
 * summary alert, later ones just update the status line and the row's "Pending restart" state.
 * Check for Updates checks the selection when one exists, or every installed library when nothing
 * is selected.
 *
 * <p>Deliberately a thin shell. Everything worth testing lives in the headless
 * {@code plugin} package, because this project has no infrastructure for testing JavaFX windows.
 */
public final class PluginWindow {

    private static final Logger log = Log.get(PluginWindow.class);

    private static PluginWindow instance;

    /** Key in {@link AppPreferences} for "skip the trust-on-first-use install warning from now on". */
    private static final String SKIP_INSTALL_WARNING_KEY = "plugin.skipInstallWarning";

    private final PluginCatalog catalog;
    private final AppPreferences preferences;
    private final java.util.function.BooleanSupplier tryReloadLibraries;
    private final java.util.function.ToIntFunction<String> liveNodeCount;
    private final Stage stage;
    private final TableView<Row> table = new TableView<>();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final Label status = new Label();

    /** Latest known release per library id, from the last explicit update check. */
    private final Map<String, GitHubReleases.Release> latestKnown = new HashMap<>();

    /**
     * Library ids whose catalog/disk change has landed but whose hot reload hasn't run yet because
     * a node-library node was live on the canvas at the time. Cleared entirely the moment a reload
     * does succeed — that reload always rebuilds from the whole current catalog, so nothing is left
     * outstanding once it runs. Its emptiness also gates the one-time-per-session summary alert.
     */
    private final Set<String> pendingRestart = new LinkedHashSet<>();

    /** One table row. A view model, so the table never reaches into the catalog mid-render. */
    public static final class Row {
        private final PluginCatalog.Installed installed;
        private final SimpleStringProperty latest = new SimpleStringProperty("—");
        private final SimpleStringProperty state = new SimpleStringProperty("");
        /** Non-null while an update is downloading for this row; the Status cell shows its progress
         *  instead of {@link #state} for as long as this is set. */
        private final SimpleObjectProperty<Task<Void>> activeInstall = new SimpleObjectProperty<>();

        Row(PluginCatalog.Installed installed) {
            this.installed = installed;
        }

        public String getId() {
            return installed.id();
        }

        public String getName() {
            return installed.name();
        }

        public String getRepository() {
            return installed.repository() == null ? "—" : installed.repository();
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

        public SimpleObjectProperty<Task<Void>> activeInstallProperty() {
            return activeInstall;
        }
    }

    /** One row of {@link AddFromUrlDialog}'s results table: a release asset and whether it's installed. */
    private static final class AssetRow {
        private final GitHubReleases.Asset asset;
        private final SimpleStringProperty status;
        private boolean installed;
        /** Non-null while this asset is downloading; the Status cell shows its progress instead of
         *  {@link #status} for as long as this is set. */
        private final SimpleObjectProperty<Task<Void>> activeInstall = new SimpleObjectProperty<>();

        AssetRow(GitHubReleases.Asset asset, boolean installed) {
            this.asset = asset;
            this.installed = installed;
            this.status = new SimpleStringProperty(installed ? "Installed" : "Not installed");
        }
    }

    /**
     * The "Add from URL…" window: a repository field and, once looked up, every node-library
     * package the latest release publishes, as a table with an Add button per not-yet-installed row.
     * Replaces the old text-then-dropdown-then-confirm sequence — a monorepo release can carry many
     * packages, and the user should see all of them at once rather than picking exactly one blind.
     *
     * <p>Non-modal and owned by the main window, for the same reason {@code PluginWindow} itself is:
     * installing is network-bound and the user shouldn't be blocked from the rest of the app while
     * it runs, and a table lets several packages be added one after another without reopening.
     */
    private final class AddFromUrlDialog {
        private final Stage dialogStage = new Stage();
        private final TextField urlField;
        private final ObservableList<AssetRow> assetRows = FXCollections.observableArrayList();
        private final TableView<AssetRow> assetTable = new TableView<>();
        private final Label dialogStatus = new Label();
        private String repositoryUrl;
        private GitHubReleases.Release release;

        AddFromUrlDialog(String initialUrl) {
            dialogStage.initOwner(stage);
            dialogStage.setTitle("Add a node library");

            urlField = new TextField(initialUrl == null || initialUrl.isBlank()
                    ? "https://github.com/" : initialUrl);
            HBox.setHgrow(urlField, Priority.ALWAYS);
            Button lookUp = new Button("Look Up");
            lookUp.setDefaultButton(true);
            lookUp.setOnAction(e -> lookUp());
            urlField.setOnAction(e -> lookUp());
            HBox urlRow = new HBox(8, new Label("Repository:"), urlField, lookUp);
            urlRow.setAlignment(Pos.CENTER_LEFT);
            urlRow.setPadding(new Insets(10, 10, 6, 10));

            assetTable.setItems(assetRows);
            assetTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            TableColumn<AssetRow, String> nameCol = new TableColumn<>("Package");
            nameCol.setPrefWidth(260);
            nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().asset.name()));
            TableColumn<AssetRow, String> sizeCol = new TableColumn<>("Size");
            sizeCol.setPrefWidth(90);
            sizeCol.setCellValueFactory(data -> new SimpleStringProperty(formatSize(data.getValue().asset.sizeBytes())));
            TableColumn<AssetRow, AssetRow> statusCol = new TableColumn<>("Status");
            statusCol.setPrefWidth(130);
            statusCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue()));
            statusCol.setCellFactory(col -> statusCell(row -> row.status, row -> row.activeInstall));
            TableColumn<AssetRow, Void> actionCol = new TableColumn<>("");
            actionCol.setPrefWidth(80);
            actionCol.setCellFactory(col -> new TableCell<>() {
                private final Button addButton = new Button("Add");
                {
                    addButton.setOnAction(e -> install(getTableRow().getItem()));
                }

                @Override
                protected void updateItem(Void ignored, boolean empty) {
                    super.updateItem(ignored, empty);
                    AssetRow row = empty || getTableRow() == null ? null : getTableRow().getItem();
                    setGraphic(row == null || row.installed ? null : addButton);
                }
            });
            assetTable.getColumns().setAll(List.of(nameCol, sizeCol, statusCol, actionCol));
            assetTable.setPlaceholder(new Label("Enter a repository URL and press “Look Up”."));

            dialogStatus.setWrapText(true);
            dialogStatus.setPadding(new Insets(0, 10, 8, 10));

            BorderPane root = new BorderPane();
            root.setTop(urlRow);
            root.setCenter(assetTable);
            root.setBottom(dialogStatus);
            dialogStage.setScene(new Scene(root, 560, 360));
        }

        void show() {
            dialogStage.show();
            dialogStage.toFront();
        }

        void showAndLookUp() {
            show();
            lookUp();
        }

        private void lookUp() {
            String url = urlField.getText() == null ? "" : urlField.getText().trim();
            if (url.isBlank()) {
                dialogStatus.setText("Enter a repository URL first.");
                return;
            }
            assetRows.clear();
            dialogStatus.setText("Looking up the latest release of " + url + "…");
            Task<GitHubReleases.Release> task = new Task<>() {
                @Override
                protected GitHubReleases.Release call() throws Exception {
                    return GitHubReleases.latest(url, null)
                            .orElseThrow(() -> new IllegalStateException("No release information returned"));
                }
            };
            task.setOnSucceeded(e -> {
                repositoryUrl = url;
                release = task.getValue();
                assetRows.setAll(release.assets().stream()
                        .map(asset -> new AssetRow(asset, isAssetInstalled(repositoryUrl, release, asset)))
                        .toList());
                dialogStatus.setText("Release " + release.tagName() + " — " + release.assets().size()
                        + (release.assets().size() == 1 ? " package." : " packages."));
            });
            task.setOnFailed(e -> {
                Throwable failure = task.getException();
                log.error("Node-library lookup failed", failure);
                dialogStatus.setText("Failed: " + failure.getMessage());
            });
            Thread thread = new Thread(task, "housegraph-plugin-lookup");
            thread.setDaemon(true);
            thread.start();
        }

        private void install(AssetRow row) {
            if (row == null || row.installed) {
                return;
            }
            confirmThenInstall(dialogStage, repositoryUrl, release, row.asset, () -> {
                row.installed = true;
                row.status.set("Installed");
                assetTable.refresh(); // the Add button in the action column keys off row.installed
            }, row);
        }
    }

    /**
     * Whether a release asset corresponds to an already-installed library from this repository,
     * matched the same way {@link GitHubReleases.Release#assetFor} resolves a wanted id: an
     * installed entry counts if its id would resolve to this exact asset.
     */
    private boolean isAssetInstalled(String repositoryUrl, GitHubReleases.Release release, GitHubReleases.Asset asset) {
        return catalog.all().stream()
                .filter(installed -> repositoryUrl.equals(installed.repository()))
                .anyMatch(installed -> release.assetFor(installed.id())
                        .map(found -> found.name().equals(asset.name()))
                        .orElse(false));
    }

    /**
     * Opens the window, or brings it to the front if already open.
     *
     * @param catalog            the installed libraries
     * @param preferences        where the "don't show the install warning again" choice is persisted
     * @param tryReloadLibraries called on the FX thread after a change that needs the node registry
     *                           and Add-Node menu rebuilt; attempts the rebuild and returns whether
     *                           it actually ran, which is false whenever a node-library node is live
     *                           on the canvas — the change still lands on disk, it just waits for a
     *                           restart instead
     * @param liveNodeCount      how many nodes from a given library are on the canvas right now
     */
    public static void show(PluginCatalog catalog, AppPreferences preferences,
                            java.util.function.BooleanSupplier tryReloadLibraries,
                            java.util.function.ToIntFunction<String> liveNodeCount) {
        if (instance == null) {
            instance = new PluginWindow(catalog, preferences, tryReloadLibraries, liveNodeCount);
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
    public static void showAndInstall(PluginCatalog catalog, AppPreferences preferences,
                                      java.util.function.BooleanSupplier tryReloadLibraries,
                                      java.util.function.ToIntFunction<String> liveNodeCount,
                                      String repositoryUrl) {
        show(catalog, preferences, tryReloadLibraries, liveNodeCount);
        instance.installFrom(repositoryUrl);
    }

    private PluginWindow(PluginCatalog catalog, AppPreferences preferences,
                         java.util.function.BooleanSupplier tryReloadLibraries,
                         java.util.function.ToIntFunction<String> liveNodeCount) {
        this.catalog = catalog;
        this.preferences = preferences;
        this.tryReloadLibraries = tryReloadLibraries;
        this.liveNodeCount = liveNodeCount;
        stage = new Stage();
        stage.setTitle("HouseGraph Node Libraries");
        stage.setScene(new Scene(buildRoot(), 1080, 420));
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
        TableColumn<Row, Row> statusCol = new TableColumn<>("Status");
        statusCol.setPrefWidth(200);
        statusCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue()));
        statusCol.setCellFactory(col -> statusCell(Row::stateProperty, Row::activeInstallProperty));
        table.getColumns().setAll(
                column("Id", 170, row -> new SimpleStringProperty(row.getId())),
                column("Name", 150, row -> new SimpleStringProperty(row.getName())),
                column("Repository", 220, row -> new SimpleStringProperty(row.getRepository())),
                column("Installed", 90, row -> new SimpleStringProperty(row.getVersion())),
                column("Latest", 90, Row::latestProperty),
                column("API", 60, row -> new SimpleStringProperty(row.getApiVersion())),
                column("Enabled", 70, row -> new SimpleStringProperty(row.getEnabled())),
                statusCol);
        // Every column is sortable via its header by default (TableView needs no extra wiring for a
        // String column) — click Repository to group libraries that share one, the way Update and
        // Remove already let you act on a multi-selection of them at once.
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

    /**
     * A Status-column cell shared by both tables in this window: normally the row's plain status
     * text, but a live {@link TaskProgressBar} for as long as {@code taskOf} returns a non-null
     * {@link Task} for that row — which is how download progress ends up next to the row it belongs
     * to instead of in one shared bar for the whole window. Watches {@code taskOf}'s property
     * directly, so it re-renders itself the moment the row starts or finishes downloading; the caller
     * doesn't need to remember to refresh the table.
     */
    private static <T> TableCell<T, T> statusCell(java.util.function.Function<T, SimpleStringProperty> textOf,
                                                  java.util.function.Function<T, SimpleObjectProperty<Task<Void>>> taskOf) {
        return new TableCell<>() {
            private T watched;
            private final ChangeListener<Task<Void>> listener = (obs, was, now) -> render();

            @Override
            protected void updateItem(T row, boolean empty) {
                super.updateItem(row, empty);
                if (watched != null) {
                    taskOf.apply(watched).removeListener(listener);
                    watched = null;
                }
                if (!empty && row != null) {
                    taskOf.apply(row).addListener(listener);
                    watched = row;
                }
                render();
            }

            private void render() {
                textProperty().unbind();
                T row = getItem();
                if (isEmpty() || row == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Task<Void> task = taskOf.apply(row).get();
                if (task != null) {
                    TaskProgressBar bar = new TaskProgressBar();
                    bar.track(task);
                    setGraphic(bar);
                    setText(null);
                } else {
                    setGraphic(null);
                    textProperty().bind(textOf.apply(row));
                }
            }
        };
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
            if (pendingRestart.contains(row.getId())) {
                int live = liveNodeCount.applyAsInt(row.getId());
                row.stateProperty().set(live > 0
                        ? "Pending restart (" + live + (live == 1 ? " node live)" : " nodes live)")
                        : "Pending restart");
            }
        }
        table.refresh();
    }

    // --- Actions --------------------------------------------------------------------------------

    private void promptAndInstall() {
        new AddFromUrlDialog(null).show();
    }

    /**
     * Opens the Add-from-URL window already looking up {@code repositoryUrl}, so a caller that
     * already knows the repository doesn't make the user retype it.
     */
    public void installFrom(String repositoryUrl) {
        installFrom(repositoryUrl, null);
    }

    /**
     * @param wantedPluginId the library to take from the release when the repository publishes
     *                       several (an update knows which); null to show every package the
     *                       repository's latest release publishes and let the user pick
     */
    public void installFrom(String repositoryUrl, String wantedPluginId) {
        if (wantedPluginId == null) {
            new AddFromUrlDialog(repositoryUrl).showAndLookUp();
            return;
        }
        runOffThread("Looking up the latest release of " + repositoryUrl + "…", () -> {
            GitHubReleases.Release release = GitHubReleases.latest(repositoryUrl, null)
                    .orElseThrow(() -> new IllegalStateException("No release information returned"));
            Platform.runLater(() -> release.assetFor(wantedPluginId).ifPresentOrElse(
                    asset -> confirmThenInstall(stage, repositoryUrl, release, asset, () -> {
                    }),
                    () -> status.setText("Release " + release.tagName() + " has no jar for \""
                            + wantedPluginId + "\".")));
        });
    }

    /**
     * Shows the trust-on-first-use warning — unless the user has already turned it off — then
     * installs on success. Shared by the legacy named-install path and every row of
     * {@link AddFromUrlDialog}'s table, so the security-relevant wording lives in one place.
     *
     * <p>Deliberately says nothing about the release or asset size: that is already on screen, in
     * the Add-from-URL table's row the user just clicked "Add" on. Repeating it here just buried the
     * warning that matters underneath information the user had already seen.
     *
     * @param owner       the window the confirmation belongs to — the main window for a named
     *                    install, the Add-from-URL window for a row's "Add"
     * @param onInstalled run on the FX thread after a successful install, in addition to the usual
     *                    catalog refresh
     */
    private void confirmThenInstall(Stage owner, String repositoryUrl, GitHubReleases.Release release,
                                    GitHubReleases.Asset asset, Runnable onInstalled) {
        confirmThenInstall(owner, repositoryUrl, release, asset, onInstalled, null);
    }

    /**
     * @param sourceRow the {@link AddFromUrlDialog} row this install came from, so the download's
     *                  progress can be shown in that row's Status cell; null when there is no such
     *                  row (the legacy named-install path used by the missing-library prompt), in
     *                  which case progress simply isn't shown anywhere but the status line's text.
     */
    private void confirmThenInstall(Stage owner, String repositoryUrl, GitHubReleases.Release release,
                                    GitHubReleases.Asset asset, Runnable onInstalled, AssetRow sourceRow) {
        if (skipInstallWarning()) {
            downloadAndInstall(repositoryUrl, release, asset, onInstalled, sourceRow);
            return;
        }

        // Trust-on-first-use, stated plainly. This matters most when a save file proposed the
        // repository: that is untrusted input asking to download and execute code, so it must never
        // install silently. And there is no sandbox to fall back on.
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(owner);
        confirm.setTitle("Install node library");
        confirm.setHeaderText("Install " + asset.name() + " from " + repositoryUrl + "?");

        Label warning = new Label("A node library runs with the same access as HouseGraph itself: "
                + "your files, your network, and your saved secrets. Install it only if you trust its "
                + "author, exactly as you would any program you downloaded.");
        warning.setWrapText(true);
        CheckBox dontShowAgain = new CheckBox("Don't show this warning again");
        VBox content = new VBox(12, warning, dontShowAgain);
        content.setPadding(new Insets(4, 0, 0, 0));
        confirm.getDialogPane().setContent(content);
        confirm.getDialogPane().setMinWidth(480);

        if (confirm.showAndWait().filter(button -> button.getButtonData().isDefaultButton()).isEmpty()) {
            status.setText("Install cancelled.");
            return;
        }
        if (dontShowAgain.isSelected()) {
            setSkipInstallWarning(true);
        }
        downloadAndInstall(repositoryUrl, release, asset, onInstalled, sourceRow);
    }

    private void downloadAndInstall(String repositoryUrl, GitHubReleases.Release release,
                                    GitHubReleases.Asset asset, Runnable onInstalled, AssetRow sourceRow) {
        runOffThread("Downloading " + asset.name() + "…", () ->
                installTracked(sourceRow == null ? null : sourceRow.activeInstall,
                        progress -> PluginInstaller.install(repositoryUrl, release, asset, catalog,
                                asset.sizeBytes() > 0 ? progress : PluginInstaller.ProgressListener.NONE)),
                () -> {
            String id = catalogIdOf(repositoryUrl);
            latestKnown.put(id, release);
            librariesChanged("Installed " + asset.name() + ".", List.of(id));
            onInstalled.run();
        });
    }

    private boolean skipInstallWarning() {
        return preferences.get(SKIP_INSTALL_WARNING_KEY).map(Boolean::parseBoolean).orElse(false);
    }

    private void setSkipInstallWarning(boolean skip) {
        preferences.put(SKIP_INSTALL_WARNING_KEY, Boolean.toString(skip));
        preferences.save();
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

    /** One library's resolved update: what it would become, and from where. */
    private record UpdatePlan(PluginCatalog.Installed installed, GitHubReleases.Release release,
                              GitHubReleases.Asset asset) {
    }

    /**
     * Looks up every selected library's latest release, then shows one confirmation summarising the
     * whole batch — name, current and new version, and size for each — instead of a separate dialog
     * per library. A library id is passed with each lookup, the same as before: a monorepo's release
     * carries a jar per library, and an update must take the one it already has rather than asking.
     */
    private void updateSelected() {
        List<Row> selected = selectedRows();
        if (selected.isEmpty()) {
            return;
        }
        List<String> noRepository = new ArrayList<>();
        List<PluginCatalog.Installed> toUpdate = new ArrayList<>();
        for (Row row : selected) {
            PluginCatalog.Installed installed = catalog.byId(row.getId()).orElseThrow();
            if (installed.repository() == null) {
                noRepository.add(row.getId());
                continue;
            }
            toUpdate.add(installed);
        }
        if (!noRepository.isEmpty()) {
            status.setText("No recorded repository to update from: " + String.join(", ", noRepository) + ".");
        }
        if (toUpdate.isEmpty()) {
            return;
        }

        List<UpdatePlan> plans = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        runOffThread("Checking " + toUpdate.size() + (toUpdate.size() == 1 ? " library" : " libraries")
                + " for updates…", () -> {
            for (PluginCatalog.Installed installed : toUpdate) {
                try {
                    GitHubReleases.Release release = GitHubReleases.latest(installed.repository(), null)
                            .orElseThrow(() -> new IllegalStateException("No release information returned"));
                    GitHubReleases.Asset asset = release.assetFor(installed.id())
                            .orElseThrow(() -> new IllegalStateException("Release " + release.tagName()
                                    + " has no jar for \"" + installed.id() + "\""));
                    plans.add(new UpdatePlan(installed, release, asset));
                } catch (Exception e) {
                    failed.add(installed.id() + " (" + e.getMessage() + ")");
                }
            }
        }, () -> confirmThenUpdateAll(plans, failed));
    }

    /** Shows the one batch confirmation, then installs every accepted update. */
    private void confirmThenUpdateAll(List<UpdatePlan> plans, List<String> lookupFailures) {
        List<UpdatePlan> outdated = plans.stream()
                .filter(plan -> !plan.release().version().equals(plan.installed().version())).toList();
        List<UpdatePlan> upToDate = plans.stream()
                .filter(plan -> plan.release().version().equals(plan.installed().version())).toList();
        if (outdated.isEmpty()) {
            StringBuilder message = new StringBuilder(upToDate.isEmpty() ? "Nothing to update." : "Already up to date.");
            if (!lookupFailures.isEmpty()) {
                message.append(" Could not check: ").append(String.join(", ", lookupFailures)).append('.');
            }
            status.setText(message.toString());
            return;
        }

        boolean plural = outdated.size() > 1;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(stage);
        confirm.setTitle("Update node libraries");
        confirm.setHeaderText("Update " + outdated.size() + (plural ? " libraries?" : " library?"));
        StringBuilder body = new StringBuilder();
        long totalBytes = 0;
        for (UpdatePlan plan : outdated) {
            body.append("• ").append(plan.installed().id()).append(": ").append(plan.installed().version())
                    .append(" → ").append(plan.release().version())
                    .append(" (").append(formatSize(plan.asset().sizeBytes())).append(")\n");
            totalBytes += plan.asset().sizeBytes();
        }
        if (!upToDate.isEmpty()) {
            List<String> ids = upToDate.stream().map(plan -> plan.installed().id()).toList();
            body.append("\nAlready up to date: ").append(String.join(", ", ids)).append('\n');
        }
        if (!lookupFailures.isEmpty()) {
            body.append("\nCould not check: ").append(String.join(", ", lookupFailures)).append('\n');
        }
        body.append("\nTotal download: ").append(formatSize(totalBytes)).append(".\n\n")
                .append("A node library runs with the same access as HouseGraph itself: your files, your "
                        + "network, and your saved secrets. Update only if you still trust its author.");
        confirm.setContentText(body.toString());
        confirm.getDialogPane().setMinWidth(520);
        if (confirm.showAndWait().filter(button -> button.getButtonData().isDefaultButton()).isEmpty()) {
            status.setText("Update cancelled.");
            return;
        }
        installAllUpdates(outdated);
    }

    /** A library whose nodes are live on the canvas updates its jar and catalog entry just the same. */
    private void installAllUpdates(List<UpdatePlan> plans) {
        List<String> installedIds = new ArrayList<>();
        List<String> failedInstalls = new ArrayList<>();
        // Resolved here, on the FX thread, rather than inside the background loop below: `rows` is
        // an FX-owned list and the loop's thread has no business reading it directly. A plain loop
        // rather than Collectors.toMap because a plan's row can legitimately be null (Map.merge,
        // which toMap uses internally, rejects null values outright).
        Map<UpdatePlan, Row> rowFor = new HashMap<>();
        for (UpdatePlan plan : plans) {
            rowFor.put(plan, findRow(plan.installed().id()));
        }
        // Each library gets its own row-bound task, so its progress shows in that row's own Status
        // cell rather than one bar for the whole batch — libraries are installed one at a time
        // regardless, so this is just where that already-sequential work reports to.
        runOffThread("Updating " + plans.size() + (plans.size() == 1 ? " library…" : " libraries…"), () -> {
            for (UpdatePlan plan : plans) {
                Row row = rowFor.get(plan);
                try {
                    installTracked(row == null ? null : row.activeInstallProperty(), progress ->
                            PluginInstaller.install(plan.installed().repository(), plan.release(), plan.asset(),
                                    catalog, plan.asset().sizeBytes() > 0 ? progress : PluginInstaller.ProgressListener.NONE));
                    latestKnown.put(plan.installed().id(), plan.release());
                    installedIds.add(plan.installed().id());
                } catch (Exception e) {
                    failedInstalls.add(plan.installed().id() + " (" + e.getMessage() + ")");
                }
            }
        }, () -> {
            StringBuilder message = new StringBuilder();
            if (!installedIds.isEmpty()) {
                message.append("Updated ").append(String.join(", ", installedIds)).append('.');
            }
            if (!failedInstalls.isEmpty()) {
                message.append(message.isEmpty() ? "" : " ").append("Failed: ")
                        .append(String.join(", ", failedInstalls)).append('.');
            }
            librariesChanged(message.toString(), installedIds);
        });
    }

    private void toggleSelected() {
        List<Row> selected = selectedRows();
        if (selected.isEmpty()) {
            return;
        }
        List<String> changed = new ArrayList<>();
        int enabledCount = 0;
        int disabledCount = 0;
        for (Row row : selected) {
            PluginCatalog.Installed installed = catalog.byId(row.getId()).orElseThrow();
            boolean enabling = !installed.enabled();
            catalog.setEnabled(row.getId(), enabling);
            changed.add(row.getId());
            if (enabling) {
                enabledCount++;
            } else {
                disabledCount++;
            }
        }
        catalog.save();
        StringBuilder message = new StringBuilder();
        if (enabledCount > 0) {
            message.append("Enabled ").append(enabledCount).append(enabledCount == 1 ? " library. " : " libraries. ");
        }
        if (disabledCount > 0) {
            message.append("Disabled ").append(disabledCount).append(disabledCount == 1 ? " library." : " libraries.");
        }
        librariesChanged(message.toString().trim(), changed);
    }

    /** One confirmation for the whole selection, listing each library, rather than one per removal. */
    private void removeSelected() {
        List<Row> selected = selectedRows();
        if (selected.isEmpty()) {
            return;
        }
        boolean plural = selected.size() > 1;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(stage);
        confirm.setTitle("Remove node libraries");
        confirm.setHeaderText(plural ? "Remove " + selected.size() + " libraries?"
                : "Remove \"" + selected.get(0).getId() + "\"?");
        StringBuilder body = new StringBuilder();
        if (plural) {
            for (Row row : selected) {
                body.append("• ").append(row.getId()).append(" (").append(row.getVersion()).append(")\n");
            }
            body.append('\n');
        }
        body.append("Graphs using ").append(plural ? "their nodes" : "its nodes").append(" will still open "
                + "— those nodes are kept as placeholders and come back if you reinstall ")
                .append(plural ? "them" : "it").append('.');
        confirm.setContentText(body.toString());
        confirm.getDialogPane().setMinWidth(420);
        if (confirm.showAndWait().filter(button -> button.getButtonData().isDefaultButton()).isEmpty()) {
            return;
        }
        List<String> ids = selected.stream().map(Row::getId).toList();
        for (String id : ids) {
            catalog.remove(id);
        }
        catalog.save();
        librariesChanged("Removed " + ids.size() + (plural ? " libraries. Their jars are"
                : " library. Its jar is") + " cleaned up at next startup.", ids);
    }

    /**
     * Shows one summary alert the first time in a session that a change is left pending a restart.
     * A no-op on every later deferral — the row's "Pending restart" state and the status line carry
     * it from there, so the user isn't interrupted by a dialog for every subsequent bulk action.
     */
    private void notePendingRestart() {
        boolean plural = pendingRestart.size() > 1;
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setHeaderText((plural ? pendingRestart.size() + " libraries changed"
                : "\"" + pendingRestart.iterator().next() + "\" changed") + " — restart to apply");
        alert.setContentText("A node from a node library is on the canvas right now, and Java can't "
                + "reload " + (plural ? "their" : "its") + " classes while it's there — that would "
                + "leave it bound to a discarded class. The change is already saved and will take "
                + "effect the next time you start HouseGraph. Further changes made before then will "
                + "wait too.\n\n" + String.join(", ", pendingRestart));
        alert.getDialogPane().setMinWidth(460);
        alert.showAndWait();
    }

    /** The visible row for an already-installed library, or null if it isn't in the table right now. */
    private Row findRow(String id) {
        return rows.stream().filter(row -> row.getId().equals(id)).findFirst().orElse(null);
    }

    private List<Row> selectedRows() {
        List<Row> selected = new ArrayList<>(table.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            status.setText("Select at least one library first.");
        }
        return selected;
    }

    /**
     * Called after a catalog/disk mutation has already happened, to try to bring the running session
     * in line with it. Attempts the hot reload; if it can't run (a node-library node is live on the
     * canvas), the affected ids are recorded as pending instead of being lost. A successful reload
     * always rebuilds from the whole current catalog, so it clears every pending id, not just the
     * ones from this particular action.
     */
    private void librariesChanged(String message, List<String> affectedIds) {
        boolean reloaded = tryReloadLibraries.getAsBoolean();
        if (reloaded) {
            pendingRestart.clear();
        } else {
            boolean firstThisSession = pendingRestart.isEmpty();
            pendingRestart.addAll(affectedIds);
            if (firstThisSession) {
                notePendingRestart();
            }
        }
        refresh();
        status.setText(message + (reloaded ? "" : " Pending restart."));
    }

    // --- Off-thread plumbing ---------------------------------------------------------------------

    private void runOffThread(String startedMessage, Callable work) {
        runOffThread(startedMessage, work, this::refresh);
    }

    /** Network work never runs on the FX thread; failures surface as an alert, as elsewhere in the UI. */
    private void runOffThread(String startedMessage, Callable work, Runnable onSuccess) {
        status.setText(startedMessage);
        runTask(new Task<>() {
            @Override
            protected Void call() throws Exception {
                work.call();
                return null;
            }
        }, onSuccess);
    }

    private void runTask(Task<Void> task, Runnable onSuccess) {
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

    private interface ProgressCallable {
        void call(PluginInstaller.ProgressListener progress) throws Exception;
    }

    /**
     * Runs {@code body} inside its own {@link Task}, purely so it gets a
     * {@link PluginInstaller.ProgressListener} wired to that task's progress — and, when
     * {@code trackedBy} is given (a row's {@code activeInstall} property), publishes the task there
     * so that row's Status cell shows it for as long as it runs. {@code trackedBy} is a JavaFX
     * property and must only be touched on the FX thread, so it's set and cleared via
     * {@link Platform#runLater}; the calling thread is always {@link #runOffThread}'s background
     * thread, which is why the inner task is run synchronously here rather than handed to a thread
     * of its own — JavaFX marshals a {@code Task}'s own property updates to the FX thread regardless
     * of which thread drives it. {@code body}'s exception is caught and rethrown by hand rather than
     * read back via {@code task.getException()} afterward, because that getter — unlike
     * {@code updateProgress} — is thread-checked and throws off the FX thread.
     */
    private void installTracked(SimpleObjectProperty<Task<Void>> trackedBy, ProgressCallable body) throws Exception {
        Exception[] failure = new Exception[1];
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    body.call(this::updateProgress);
                } catch (Exception e) {
                    failure[0] = e;
                }
                return null;
            }
        };
        if (trackedBy != null) {
            Platform.runLater(() -> trackedBy.set(task));
        }
        try {
            task.run();
            if (failure[0] != null) {
                throw failure[0];
            }
        } finally {
            if (trackedBy != null) {
                Platform.runLater(() -> trackedBy.set(null));
            }
        }
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
