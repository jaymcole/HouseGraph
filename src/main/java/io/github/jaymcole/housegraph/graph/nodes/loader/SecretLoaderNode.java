package io.github.jaymcole.housegraph.graph.nodes.loader;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.storage.SecretsStore;
import io.github.jaymcole.housegraph.ui.NodeContentProvider;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;

import java.util.List;
import java.util.Map;

/**
 * Resolves a named secret at process() time from the encrypted {@link SecretsStore}
 * (managed via the Secrets editor), falling back to a real OS environment variable of
 * the same name. The store is read fresh each call, so a secret edited in the editor is
 * picked up on the next trigger.
 * <p>
 * Only the selected <em>key</em> is part of the node's saved state; the resolved value
 * output is marked secret ({@link NodeVariable#markSecret()}), so a saved graph records
 * which secret to use, never the secret itself.
 * <p>
 * The key is chosen from a dropdown populated with the store's current keys, refreshed
 * each time it's opened — so this node has no inputs and, with nothing to trigger, no
 * flow ports either.
 */
@Display.Name("Secret Loader")
public class SecretLoaderNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<String> value = new NodeVariable<>("Value", String.class).markSecret();
    private String selectedKey;

    @Override
    public void process() {
        value.setValue(selectedKey == null ? null : resolve(selectedKey));
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
        addOutput(value);
    }

    @Override
    public Map<String, String> saveState() {
        return selectedKey == null ? Map.of() : Map.of("key", selectedKey);
    }

    @Override
    public void loadState(Map<String, String> state) {
        String key = state.get("key");
        selectedKey = (key == null || key.isBlank()) ? null : key;
    }

    @Override
    public Node createNodeContent() {
        ComboBox<String> keyChooser = new ComboBox<>();
        keyChooser.setPromptText("Select key...");
        keyChooser.setMaxWidth(Double.MAX_VALUE);
        keyChooser.getItems().setAll(availableKeys());
        if (selectedKey != null) {
            keyChooser.setValue(selectedKey);
        }
        // Secrets may have been edited since the node was created; refresh right before
        // the list is shown rather than only once at construction time.
        keyChooser.setOnShowing(event -> keyChooser.getItems().setAll(availableKeys()));
        keyChooser.setOnAction(event -> selectedKey = keyChooser.getValue());
        return keyChooser;
    }

    private static List<String> availableKeys() {
        try {
            return SecretsStore.open().keys();
        } catch (RuntimeException e) {
            System.err.println("Could not list secrets: " + e.getMessage());
            return List.of();
        }
    }

    private static String resolve(String key) {
        try {
            String fromStore = SecretsStore.open().get(key);
            if (fromStore != null) {
                return fromStore;
            }
        } catch (RuntimeException e) {
            System.err.println("Could not read secret from store: " + e.getMessage());
        }
        return System.getenv(key);
    }
}
