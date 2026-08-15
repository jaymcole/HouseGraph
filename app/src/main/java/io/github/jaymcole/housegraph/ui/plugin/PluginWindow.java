package io.github.jaymcole.housegraph.ui.plugin;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.plugin.GitHubReleases;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.plugin.PluginInstaller;
import io.github.jaymcole.housegraph.plugin.PluginTrust;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * selection at once. The catalog/disk change always happens immediately — the version-stamped
 * jar path and plain JSON catalog write are safe even while a library's nodes are live. What
 * defers is the in-memory hot reload: rebuilding the shared class loader would leave any
 * node-library node currently on the canvas bound to a discarded {@code Class}, so a reload only
 * runs when the canvas has none. When it can't, the change is left pending and takes effect on
 * the next restart; the first time that happens in a session gets one summary alert, later ones
 * just update the status line and the row's "Pending restart" state. Check for Updates checks the
 * selection when one exists, or every installed library when nothing is selected.
 *
 * <p>Deliberately a thin shell. Everything worth testing lives in the headless
 * {@code plugin} package, because this project has no infrastructure for testing JavaFX windows.
 */
public final class PluginWindow {

    private static final Logger log = Log.get(PluginWindow.class);

    private static PluginWindow instance;

    private final PluginCatalog catalog;
    private final PluginTrust trust;
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
     * @param trust              which repositories may install without asking; the only place it is
     *                           ever added to is this window's install confirmation
     * @param tryReloadLibraries called on the FX thread after a change that needs the node registry
     *                           and Add-Node menu rebuilt; attempts the rebuild and returns whether
     *                           it actually ran, which is false whenever a node-library node is live
     *                           on the canvas — the change still lands on disk, it just waits for a
     *                           restart instead
     * @param liveNodeCount      how many nodes from a given library are on the canvas right now
     */
    public static void show(PluginCatalog catalog, PluginTrust trust,
                            java.util.function.BooleanSupplier tryReloadLibraries,
                            java.util.function.ToIntFunction<String> liveNodeCount) {
        if (instance == null) {
            instance = new PluginWindow(catalog, trust, tryReloadLibraries, liveNodeCount);
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
    public static void showAndInstall(PluginCatalog catalog, PluginTrust trust,
                                      java.util.function.BooleanSupplier tryReloadLibraries,
                                      java.util.function.ToIntFunction<String> liveNodeCount,
                                      String repositoryUrl) {
        show(catalog, trust, tryReloadLibraries, liveNodeCount);
        instance.installFrom(repositoryUrl);
    }

    private PluginWindow(PluginCatalog catalog, PluginTrust trust,
                         java.util.function.BooleanSupplier tryReloadLibraries,
                         java.util.function.ToIntFunction<String> liveNodeCount) {
        this.catalog = catalog;
        this.trust = trust;
        this.tryReloadLibraries = tryReloadLibraries;
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

        Button trusted = new Button("Trusted Repositories…");
        trusted.setOnAction(e -> showTrustedRepositories());

        return new ToolBar(add, check, update, toggle, remove, new Separator(),
                buildAutoInstallToggle(), trusted);
    }

    /**
     * The master switch for installing without a prompt.
     *
     * <p>Turning it <em>on</em> confirms, because it changes what a graph file is able to cause: from
     * that point a save file naming an already-trusted repository triggers a download with nothing
     * further asked. Turning it off needs no confirmation — narrowing what may happen never does.
     */
    private CheckBox buildAutoInstallToggle() {
        CheckBox autoInstall = new CheckBox("Auto-install from trusted repositories");
        autoInstall.setSelected(trust.isAutoInstallEnabled());
        autoInstall.setOnAction(e -> {
            if (!autoInstall.isSelected()) {
                trust.setAutoInstallEnabled(false);
                status.setText("Auto-install off. Missing libraries will be offered, never installed.");
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.initOwner(stage);
            confirm.setTitle("Turn on auto-install");
            confirm.setHeaderText("Install missing node libraries without asking?");
            confirm.setContentText("Opening a graph will then download and run node libraries from "
                    + "repositories you have already marked trusted — including on startup, when the "
                    + "last graph is reopened.\n\n"
                    + "Only repositories you tick “Always allow…” for are ever affected; anything else "
                    + "still asks. A node library runs with the same access as HouseGraph itself, so "
                    + "keep that list to authors you would run any downloaded program from.");
            confirm.getDialogPane().setMinWidth(520);
            if (confirm.showAndWait().filter(button -> button.getButtonData().isDefaultButton()).isEmpty()) {
                autoInstall.setSelected(false);
                return;
            }
            trust.setAutoInstallEnabled(true);
            status.setText(trust.trustedRepositories().isEmpty()
                    ? "Auto-install on, but no repository is trusted yet — tick “Always allow…” when installing."
                    : "Auto-install on for " + trust.trustedRepositories().size() + " trusted repository/ies.");
        });
        return autoInstall;
    }

    /**
     * Lists the trusted repositories and allows withdrawing one. Not optional polish: trust that can
     * be granted from a dialog but never taken back from the UI would be a bad bargain to offer.
     */
    private void showTrustedRepositories() {
        List<String> trustedNow = trust.trustedRepositories();
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Trusted repositories");
        dialog.setHeaderText(trustedNow.isEmpty()
                ? "No repository is trusted yet."
                : "These repositories install and update without asking, when auto-install is on.");

        ListView<String> list = new ListView<>(FXCollections.observableArrayList(trustedNow));
        list.setPrefHeight(180);

        Button revoke = new Button("Stop trusting");
        revoke.setDisable(true);
        list.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, selected) -> revoke.setDisable(selected == null));
        revoke.setOnAction(e -> {
            String selected = list.getSelectionModel().getSelectedItem();
            if (selected != null && trust.revoke(selected)) {
                list.getItems().remove(selected);
                status.setText("No longer trusting " + selected + ".");
            }
        });

        VBox content = new VBox(8, list, revoke);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setMinWidth(560);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
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
        // "Always allow" has to mean it here too, or the checkbox's wording is a lie: a repository
        // the user already accepted, with auto-install on, downloads without asking again.
        if (trust.isTrustedForInstall(repositoryUrl)) {
            download(repositoryUrl, release, asset, false);
            return;
        }

        // Trust-on-first-use, stated plainly. This matters most when a save file proposed the
        // repository: that is untrusted input asking to download and execute code, so it must never
        // install silently. And there is no sandbox to fall back on.
        //
        // The checkbox below is the ONLY way a repository ever enters PluginTrust. That is what keeps
        // auto-install honest: the list it consults can only contain repositories the user was shown,
        // by name and size, and said yes to. Nothing a save file contains can add to it.
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(stage);
        confirm.setTitle("Install node library");
        confirm.setHeaderText("Install " + asset.name() + " from " + repositoryUrl + "?");

        Label warning = new Label("Release " + release.tagName() + " — "
                + formatSize(asset.sizeBytes()) + ".\n\n"
                + "A node library runs with the same access as HouseGraph itself: your files, your "
                + "network, and your saved secrets. Install it only if you trust its author, exactly "
                + "as you would any program you downloaded.");
        warning.setWrapText(true);

        CheckBox alwaysAllow = new CheckBox("Always allow installs and updates from this repository");
        alwaysAllow.setSelected(trust.isTrustedForInstall(repositoryUrl));
        VBox content = new VBox(12, warning, alwaysAllow);
        content.setPadding(new Insets(4, 0, 0, 0));
        confirm.getDialogPane().setContent(content);
        confirm.getDialogPane().setMinWidth(520);

        if (confirm.showAndWait().filter(button -> button.getButtonData().isDefaultButton()).isEmpty()) {
            status.setText("Install cancelled.");
            return;
        }
        download(repositoryUrl, release, asset, alwaysAllow.isSelected());
    }

    /**
     * Fetches the jar and records the result, once something has decided it may be fetched.
     *
     * @param rememberRepository whether to add the repository to {@link PluginTrust} afterwards.
     *                           Applied only on success: trusting a repository whose jar turned out
     *                           to be unloadable would be remembering the wrong half of the outcome.
     */
    private void download(String repositoryUrl, GitHubReleases.Release release,
                          GitHubReleases.Asset asset, boolean rememberRepository) {
        runOffThread("Downloading " + asset.name() + "…", () -> {
            PluginInstaller.install(repositoryUrl, release, asset, catalog);
        }, () -> {
            if (rememberRepository) {
                trust.trust(repositoryUrl);
            }
            String id = catalogIdOf(repositoryUrl);
            latestKnown.put(id, release);
            librariesChanged("Installed " + asset.name() + ".", List.of(id));
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
        // Pass the library id with each: a monorepo's release carries a jar per library, and an
        // update must take the one it already has rather than asking again. Each still goes through
        // its own confirmation dialog before downloading. A library whose nodes are live on the
        // canvas updates its jar and catalog entry just the same — see librariesChanged.
        for (PluginCatalog.Installed installed : toUpdate) {
            installFrom(installed.repository(), installed.id());
        }
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

    private void removeSelected() {
        List<Row> selected = selectedRows();
        if (selected.isEmpty()) {
            return;
        }
        boolean plural = selected.size() > 1;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(stage);
        confirm.setHeaderText(plural ? "Remove " + selected.size() + " libraries?"
                : "Remove \"" + selected.get(0).getId() + "\"?");
        confirm.setContentText("Graphs using " + (plural ? "their nodes" : "its nodes") + " will still open "
                + "— those nodes are kept as placeholders and come back if you reinstall "
                + (plural ? "them" : "it") + ".");
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
