package io.github.jaymcole.housegraph.graph.nodes.web;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.ui.view.NodeContentProvider;
import io.github.jaymcole.housegraph.web.LocalWebServer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.DirectoryChooser;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Hosts a directory of static files as a website on the local network, reachable at
 * {@code http://<name>.local:<port>/}. A long-lived resource node managed exactly like
 * {@code DiscordBotNode}: it registers a {@link LocalWebServer} under its chosen name so
 * other nodes can find it, and its liveness is user-driven (Start/Stop) rather than tied
 * to graph flow.
 * <p>
 * Configuration — the website name, the directory to serve, and the port — is authored in
 * the node's inline UI and persisted via {@link #saveState()} (a directory path, never the
 * files themselves; the site is served live from wherever it lives on disk). The actual
 * bind + mDNS advertisement runs off the UI thread so the app stays responsive, and is
 * torn down on {@link #onRemoved()} (node deleted or app shutdown).
 *
 *
 *
 * New-NetFirewallRule -DisplayName "HouseGraph Web Server 8080" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8080 -RemoteAddress LocalSubnet -Profile Any
 *
 */
@Display.Name("Web Server")
public class WebServerNode extends BaseNode implements NodeContentProvider {

    private static final Logger log = Log.get(WebServerNode.class);
    private static final int DEFAULT_PORT = 8080;

    private final LocalWebServer server = new LocalWebServer();
    private String resourceName = "housegraph";
    private String directory;
    private int port = DEFAULT_PORT;

    private TextField nameField;
    private TextField directoryField;
    private TextField portField;
    private Button browseButton;
    private Button startButton;
    private Button stopButton;
    private Button copyUrlButton;
    private Label statusLabel;

    @Override
    public void process(ProcessContext ctx) {
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
        nameField.setPromptText("Website name (→ name.local)");
        nameField.textProperty().addListener((obs, old, value) -> rename(value));

        directoryField = new TextField(directory == null ? "" : directory);
        directoryField.setPromptText("Website directory…");
        directoryField.textProperty().addListener((obs, old, value) -> directory = emptyToNull(value));

        browseButton = new Button("Browse…");
        browseButton.setOnAction(e -> chooseDirectory());

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
        return new VBox(4, nameField, dirRow, portField, buttons, copyUrlButton, statusLabel);
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
            statusLabel.setText("Pick a website directory first");
            return;
        }
        Path root = Path.of(directory);
        setEditingLocked(true);
        startButton.setDisable(true);
        statusLabel.setText("Starting…");

        Thread thread = new Thread(() -> {
            try {
                server.start(root, resourceName, port);
                Platform.runLater(() -> {
                    statusLabel.setText("Serving at " + server.url());
                    stopButton.setDisable(false);
                    copyUrlButton.setDisable(false);
                });
            } catch (Exception ex) {
                log.error("Web server start failed: {}", ex);
                Platform.runLater(() -> {
                    statusLabel.setText("Start failed — " + ex.getMessage());
                    setEditingLocked(false);
                    startButton.setDisable(false);
                });
            }
        }, "web-server-" + resourceName);
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
