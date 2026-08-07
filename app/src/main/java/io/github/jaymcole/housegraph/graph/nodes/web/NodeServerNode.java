package io.github.jaymcole.housegraph.graph.nodes.web;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.ui.view.NodeContentProvider;
import io.github.jaymcole.housegraph.web.NodeProcessServer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Launches a <b>Node.js</b> server (an Express app, a Vite dev server — anything a shell command
 * like {@code npm start} runs) from a chosen project directory and advertises it on the LAN at
 * {@code http://<name>.local:<port>/}. The Node-flavoured sibling of {@link WebServerNode}: same
 * long-lived-resource lifecycle (register under a name, user-driven Start/Stop off the UI thread,
 * torn down in {@link #onRemoved()}), but hosting is delegated to a child process instead of the
 * JVM's built-in HTTP server. It backs a {@link NodeProcessServer} rather than a
 * {@code LocalWebServer}.
 * <p>
 * Configuration — the server name, the project directory, the start command, and the port — is
 * authored inline and persisted via {@link #saveState()} (a directory path and command string,
 * never the project files themselves; the app is run live from wherever it lives on disk). The
 * process spawn and mDNS advertisement run off the UI thread so the app stays responsive.
 * <p>
 * Unlike {@code WebServerNode} there is no {@code Store} data input: a Node app manages its own
 * routes and persistence. The declared port is exported to the child as {@code PORT} and
 * advertised over mDNS, but the Node program is responsible for actually listening on it.
 */
@Display.Name("Node Server")
public class NodeServerNode extends BaseNode implements NodeContentProvider {

    private static final Logger log = Log.get(NodeServerNode.class);
    private static final int DEFAULT_PORT = 3000;
    private static final String DEFAULT_COMMAND = "npm start";

    private final NodeProcessServer server = new NodeProcessServer();
    private String resourceName = "node-app";
    private String directory;
    private String command = DEFAULT_COMMAND;
    private int port = DEFAULT_PORT;

    private TextField nameField;
    private TextField directoryField;
    private TextField commandField;
    private TextField portField;
    private Button browseButton;
    private Button startButton;
    private Button stopButton;
    private Button copyUrlButton;
    private Label statusLabel;

    @Override
    public void process(ProcessContext ctx) {
        // No data inputs; nothing to resolve. The server is driven entirely by its inline config.
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new HashMap<>();
        state.put("name", resourceName);
        if (directory != null) {
            state.put("directory", directory);
        }
        state.put("command", command);
        state.put("port", Integer.toString(port));
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        String name = state.get("name");
        if (name != null && !name.isBlank()) {
            resourceName = name;
        }
        directory = emptyToNull(state.get("directory"));
        String savedCommand = state.get("command");
        if (savedCommand != null && !savedCommand.isBlank()) {
            command = savedCommand;
        }
        port = parsePort(state.get("port"));
    }

    @Override
    protected void onActivated() {
        ResourceRegistry.shared().register(resourceName, server);
    }

    @Override
    protected void onRemoved() {
        ResourceRegistry.shared().unregister(resourceName);
        server.stop();
    }

    @Override
    public Node createNodeContent() {
        nameField = new TextField(resourceName);
        nameField.setPromptText("Server name (→ name.local)");
        nameField.textProperty().addListener((obs, old, value) -> rename(value));

        directoryField = new TextField(directory == null ? "" : directory);
        directoryField.setPromptText("Node project directory…");
        directoryField.textProperty().addListener((obs, old, value) -> directory = emptyToNull(value));

        browseButton = new Button("Browse…");
        browseButton.setOnAction(e -> chooseDirectory());

        commandField = new TextField(command);
        commandField.setPromptText("Start command (e.g. npm start)");
        commandField.textProperty().addListener((obs, old, value) -> command = value);

        portField = new TextField(Integer.toString(port));
        portField.setPromptText("Port");
        portField.setPrefColumnCount(5);
        portField.textProperty().addListener((obs, old, value) -> port = parsePort(value));

        startButton = new Button("Start");
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(e -> start());

        stopButton = new Button("Stop");
        stopButton.setMaxWidth(Double.MAX_VALUE);
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> stop());

        copyUrlButton = new Button("Copy URL");
        copyUrlButton.setMaxWidth(Double.MAX_VALUE);
        copyUrlButton.setDisable(true);
        copyUrlButton.setOnAction(e -> copyUrl());

        statusLabel = new Label("Stopped");
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");

        HBox dirRow = new HBox(6, directoryField, browseButton);
        HBox buttons = new HBox(6, startButton, stopButton);
        return new VBox(4, nameField, dirRow, commandField, portField, buttons, copyUrlButton, statusLabel);
    }

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        if (directory != null && !directory.isBlank()) {
            File current = new File(directory);
            if (current.isDirectory()) {
                chooser.setInitialDirectory(current);
            }
        }
        File chosen = chooser.showDialog(browseButton.getScene().getWindow());
        if (chosen != null) {
            directory = chosen.getAbsolutePath();
            directoryField.setText(directory);
        }
    }

    private void rename(String newName) {
        ResourceRegistry.shared().unregister(resourceName);
        resourceName = newName;
        ResourceRegistry.shared().register(resourceName, server);
    }

    private void start() {
        if (directory == null || directory.isBlank()) {
            statusLabel.setText("Pick a Node project directory first");
            return;
        }
        if (command == null || command.isBlank()) {
            statusLabel.setText("Enter a start command first");
            return;
        }
        Path root = Path.of(directory);
        String startCommand = command;
        setEditingLocked(true);
        startButton.setDisable(true);
        statusLabel.setText("Starting…");

        Thread thread = new Thread(() -> {
            try {
                server.start(root, startCommand, resourceName, port);
                Platform.runLater(() -> {
                    statusLabel.setText("Running at " + server.url());
                    stopButton.setDisable(false);
                    copyUrlButton.setDisable(false);
                });
            } catch (Exception ex) {
                log.error("Node server start failed: {}", ex);
                Platform.runLater(() -> {
                    statusLabel.setText("Start failed — " + ex.getMessage());
                    setEditingLocked(false);
                    startButton.setDisable(false);
                });
            }
        }, "node-server-" + resourceName);
        thread.setDaemon(true);
        thread.start();
    }

    private void stop() {
        server.stop();
        statusLabel.setText("Stopped");
        setEditingLocked(false);
        startButton.setDisable(false);
        stopButton.setDisable(true);
        copyUrlButton.setDisable(true);
    }

    private void copyUrl() {
        String url = server.url();
        if (url == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(url);
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("Copied " + url);
    }

    private void setEditingLocked(boolean locked) {
        nameField.setDisable(locked);
        directoryField.setDisable(locked);
        browseButton.setDisable(locked);
        commandField.setDisable(locked);
        portField.setDisable(locked);
    }

    private static int parsePort(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return (parsed >= 1 && parsed <= 65535) ? parsed : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
