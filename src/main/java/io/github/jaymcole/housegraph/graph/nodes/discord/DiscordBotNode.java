package io.github.jaymcole.housegraph.graph.nodes.discord;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.discord.DiscordBot;
import io.github.jaymcole.housegraph.discord.SlashCommandRegistry;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.storage.SecretsStore;
import io.github.jaymcole.housegraph.ui.view.NodeContentProvider;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Discord bot resource: a long-lived {@link DiscordBot} connection, managed like
 * {@code EchoResourceNode} but real. Its token comes from the encrypted secret store
 * (pick which secret holds it), so the token is never wired or saved in the graph. While
 * live it registers itself under a name so other Discord nodes reference it from
 * anywhere, and it forwards incoming messages into the resource registry as events.
 * <p>
 * Liveness is user-driven (Connect/Disconnect), independent of graph flow; the actual
 * gateway login runs off the UI thread so the app stays responsive. The connection is
 * torn down on {@link #onRemoved()} (node deleted or app shutdown).
 */
@Display.Name("Discord Bot")
public class DiscordBotNode extends BaseNode implements NodeContentProvider {

    private final DiscordBot bot = new DiscordBot();
    private String resourceName = "discord";
    private String tokenSecret;
    private String guildId;

    private TextField nameField;
    private ComboBox<String> tokenChooser;
    private TextField guildField;
    private Button connectButton;
    private Button disconnectButton;
    private Label statusLabel;

    @Override
    public void process() {
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
        if (tokenSecret != null) {
            state.put("token", tokenSecret);
        }
        if (guildId != null) {
            state.put("guild", guildId);
        }
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        String name = state.get("name");
        if (name != null && !name.isBlank()) {
            resourceName = name;
        }
        tokenSecret = emptyToNull(state.get("token"));
        guildId = emptyToNull(state.get("guild"));
    }

    @Override
    protected void onActivated() {
        bot.setMessageHandler(message -> ResourceRegistry.shared().publish(resourceName, message));
        bot.setSlashHandler(command -> ResourceRegistry.shared().publish(resourceName, command));
        ResourceRegistry.shared().register(resourceName, bot);
    }

    @Override
    protected void onRemoved() {
        ResourceRegistry.shared().unregister(resourceName);
        bot.disconnect();
    }

    @Override
    public Node createNodeContent() {
        nameField = new TextField(resourceName);
        nameField.setPromptText("Bot name");
        nameField.textProperty().addListener((obs, old, value) -> rename(value));

        tokenChooser = new ComboBox<>();
        tokenChooser.setPromptText("Token secret…");
        tokenChooser.setMaxWidth(Double.MAX_VALUE);
        tokenChooser.getItems().setAll(secretKeys());
        if (tokenSecret != null) {
            tokenChooser.setValue(tokenSecret);
        }
        tokenChooser.setOnShowing(e -> tokenChooser.getItems().setAll(secretKeys()));
        tokenChooser.setOnAction(e -> tokenSecret = tokenChooser.getValue());

        guildField = new TextField(guildId == null ? "" : guildId);
        guildField.setPromptText("Guild ID (optional, for instant slash commands)");
        guildField.textProperty().addListener((obs, old, value) -> guildId = emptyToNull(value));

        connectButton = new Button("Connect");
        connectButton.setMaxWidth(Double.MAX_VALUE);
        connectButton.setOnAction(e -> connect());

        disconnectButton = new Button("Disconnect");
        disconnectButton.setMaxWidth(Double.MAX_VALUE);
        disconnectButton.setDisable(true);
        disconnectButton.setOnAction(e -> disconnect());

        statusLabel = new Label("Disconnected");
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");

        HBox buttons = new HBox(6, connectButton, disconnectButton);
        return new VBox(4, nameField, tokenChooser, guildField, buttons, statusLabel);
    }

    private void rename(String newName) {
        ResourceRegistry.shared().unregister(resourceName);
        resourceName = newName;
        ResourceRegistry.shared().register(resourceName, bot);
    }

    private void connect() {
        String token = resolveToken();
        if (token == null) {
            statusLabel.setText("Pick a token secret first");
            return;
        }
        setEditingLocked(true);
        connectButton.setDisable(true);
        statusLabel.setText("Connecting…");

        Thread thread = new Thread(() -> {
            try {
                bot.connect(token);
                // Register the slash commands declared for this bot (see SlashCommandRegistry).
                bot.setGuildId(guildId);
                bot.syncCommands(SlashCommandRegistry.shared().commandsFor(resourceName));
                Platform.runLater(() -> {
                    statusLabel.setText("Connected as \"" + resourceName + "\"");
                    disconnectButton.setDisable(false);
                });
            } catch (Exception ex) {
                // The exception text won't contain the token, but keep the UI message
                // generic and log only the type/message, never the token itself.
                System.err.println("Discord connect failed: " + ex);
                Platform.runLater(() -> {
                    statusLabel.setText("Connect failed — check token & MESSAGE_CONTENT intent");
                    setEditingLocked(false);
                    connectButton.setDisable(false);
                });
            }
        }, "discord-connect-" + resourceName);
        thread.setDaemon(true);
        thread.start();
    }

    private void disconnect() {
        bot.disconnect();
        statusLabel.setText("Disconnected");
        setEditingLocked(false);
        connectButton.setDisable(false);
        disconnectButton.setDisable(true);
    }

    private void setEditingLocked(boolean locked) {
        nameField.setDisable(locked);
        tokenChooser.setDisable(locked);
        guildField.setDisable(locked);
    }

    private String resolveToken() {
        if (tokenSecret == null) {
            return null;
        }
        try {
            return SecretsStore.open().get(tokenSecret);
        } catch (RuntimeException e) {
            System.err.println("Could not read token secret: " + e.getMessage());
            return null;
        }
    }

    private static List<String> secretKeys() {
        try {
            return SecretsStore.open().keys();
        } catch (RuntimeException e) {
            System.err.println("Could not list secrets: " + e.getMessage());
            return List.of();
        }
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
