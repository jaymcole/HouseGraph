package io.github.jaymcole.housegraph.graph.nodes.loader;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.ui.NodeContentProvider;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a named secret at process() time: checks a ".env" file (KEY=VALUE lines,
 * looked up in the current working directory) first, then falls back to a real OS
 * environment variable of the same name. The file is re-read fresh every call rather
 * than cached, so an edited .env is picked up on the next trigger.
 * <p>
 * The key is chosen from a dropdown populated with whatever's currently in the .env
 * file, refreshed each time it's opened, rather than typed or wired in — so this node
 * has no inputs and, since there's nothing to trigger, no flow ports either.
 */
@Display.Name("Secret Loader")
public class SecretLoaderNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<String> value = new NodeVariable<>("Value", String.class);
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
    public Node createNodeContent() {
        ComboBox<String> keyChooser = new ComboBox<>();
        keyChooser.setPromptText("Select key...");
        keyChooser.setMaxWidth(Double.MAX_VALUE);
        keyChooser.getItems().setAll(sortedDotEnvKeys());
        if (selectedKey != null) {
            keyChooser.setValue(selectedKey);
        }

        // .env could have been edited since the node was created; refresh right
        // before the list is shown rather than only once at construction time.
        keyChooser.setOnShowing(event -> keyChooser.getItems().setAll(sortedDotEnvKeys()));
        keyChooser.setOnAction(event -> selectedKey = keyChooser.getValue());

        return keyChooser;
    }

    private static List<String> sortedDotEnvKeys() {
        List<String> keys = new ArrayList<>(readDotEnv().keySet());
        keys.sort(String::compareTo);
        return keys;
    }

    private static String resolve(String keyName) {
        String fromEnvFile = readDotEnv().get(keyName);
        return fromEnvFile != null ? fromEnvFile : System.getenv(keyName);
    }

    private static Map<String, String> readDotEnv() {
        Map<String, String> values = new HashMap<>();
        File file = new File(System.getProperty("user.dir"), ".env");
        if (!file.isFile()) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(file.toPath())) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String parsedKey = trimmed.substring(0, separator).trim();
                String parsedValue = unquote(trimmed.substring(separator + 1).trim());
                values.put(parsedKey, parsedValue);
            }
        } catch (IOException e) {
            System.err.println("Failed to read .env file: " + e);
        }
        return values;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
