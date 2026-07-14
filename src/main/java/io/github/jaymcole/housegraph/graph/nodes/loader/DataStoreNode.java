package io.github.jaymcole.housegraph.graph.nodes.loader;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.storage.AppDirectories;
import io.github.jaymcole.housegraph.store.DocumentStores;
import io.github.jaymcole.housegraph.store.JsonDocumentStore;
import io.github.jaymcole.housegraph.ui.view.NodeContentProvider;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A persisted JSON document store, exposed to the graph as a <b>data output</b> — wire its
 * {@code Store} output into a web-server node (or anything else that consumes a store) so the
 * connection is visible on the canvas, rather than referenced invisibly by name.
 * <p>
 * Like the other loaders (an image from disk, a secret by key), it's a pure data source: no
 * flow ports, nothing to trigger. When a downstream node pulls its output it hands back the
 * live {@link JsonDocumentStore} handle (a {@link NodeVariable#transientValue() transient}
 * value — a live handle, never persisted).
 * <p>
 * <b>Identity is the store's name.</b> The document persists to
 * {@link AppDirectories#dataStore(String)} — {@code data-stores/<name>/document.json} — keyed
 * by the user-chosen name, so the data is <em>recoverable</em>: deleting this node and
 * recreating one with the same name reopens the existing data rather than stranding it under
 * an opaque id. Renaming just points the node at a different store; the old one stays on disk
 * under its name (browse via <b>Open folder</b>) and comes back if you type its name again.
 * Nodes naming the same store share <em>one</em> instance ({@link DocumentStores}), so
 * same-name sharing is consistent. The document is never written into the graph save file.
 */
@Display.Name("Data Store")
public class DataStoreNode extends BaseNode implements NodeContentProvider {

    private static final Logger log = Log.get(DataStoreNode.class);
    private static final String DEFAULT_NAME = "store";

    private final NodeVariable<JsonDocumentStore> storeOutput =
            new NodeVariable<>("Store", JsonDocumentStore.class).transientValue();

    private String name = DEFAULT_NAME;
    /** The store currently observed for the size label; kept so its listener can be detached. */
    private JsonDocumentStore boundStore;
    private final Consumer<String> onStoreChanged = document -> refreshStatus();

    private TextField nameField;
    private Label statusLabel;

    @Override
    public void process(ProcessContext ctx) {
        storeOutput.setValue(storeFor(name));
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
        addOutput(storeOutput);
    }

    @Override
    public Map<String, String> saveState() {
        return Map.of("name", name);
    }

    @Override
    public void loadState(Map<String, String> state) {
        String saved = state.get("name");
        if (saved != null && !saved.isBlank()) {
            name = saved.trim();
        }
    }

    @Override
    protected void onActivated() {
        bind();
    }

    @Override
    protected void onRemoved() {
        // Detach our listener (the shared store instance and its file persist for reuse).
        unbind();
    }

    @Override
    public Node createNodeContent() {
        nameField = new TextField(name);
        nameField.setPromptText("Store name");
        // Commit on focus-out / Enter rather than per keystroke, so typing a name doesn't
        // create a folder for every intermediate value.
        nameField.setOnAction(e -> commitName());
        nameField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                commitName();
            }
        });

        Button openFolderButton = new Button("Open folder");
        openFolderButton.setMaxWidth(Double.MAX_VALUE);
        openFolderButton.setOnAction(e -> openStorageFolder());

        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");
        refreshStatus();

        return new VBox(4, nameField, openFolderButton, statusLabel);
    }

    private void commitName() {
        String typed = nameField.getText();
        String next = (typed == null || typed.isBlank()) ? DEFAULT_NAME : typed.trim();
        if (next.equals(name)) {
            return;
        }
        unbind();
        name = next;
        nameField.setText(name); // reflect blank → default
        bind();
    }

    /** Observes the current name's store so the size label tracks writes (incl. the website's). */
    private void bind() {
        boundStore = storeFor(name);
        boundStore.addChangeListener(onStoreChanged);
        refreshStatus();
    }

    private void unbind() {
        if (boundStore != null) {
            boundStore.removeChangeListener(onStoreChanged);
            boundStore = null;
        }
    }

    /** Opens the store's on-disk folder in the OS file manager (off the UI thread; it can block briefly). */
    private void openStorageFolder() {
        Path dir = AppDirectories.get().dataStore(name); // created on demand
        Thread thread = new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(dir.toFile());
                } else {
                    log.warn("Opening a folder isn't supported on this platform: {}", dir);
                }
            } catch (IOException | RuntimeException ex) {
                log.warn("Could not open data store folder {}: {}", dir, ex.getMessage());
            }
        }, "open-data-store-folder");
        thread.setDaemon(true);
        thread.start();
    }

    private void refreshStatus() {
        if (statusLabel == null) {
            return;
        }
        String text = (boundStore == null ? 0 : boundStore.length()) + " chars stored";
        Platform.runLater(() -> statusLabel.setText(text));
    }

    /** The shared store for {@code storeName}, backed by {@code data-stores/<name>/document.json}. */
    private static JsonDocumentStore storeFor(String storeName) {
        return DocumentStores.forFile(AppDirectories.get().dataStore(storeName).resolve("document.json"));
    }
}
