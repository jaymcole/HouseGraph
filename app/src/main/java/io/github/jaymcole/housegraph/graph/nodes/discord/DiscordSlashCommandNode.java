package io.github.jaymcole.housegraph.graph.nodes.discord;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.discord.CommandOption;
import io.github.jaymcole.housegraph.discord.DiscordOptionType;
import io.github.jaymcole.housegraph.discord.DiscordReply;
import io.github.jaymcole.housegraph.discord.DiscordSlashCommand;
import io.github.jaymcole.housegraph.discord.SlashCommandRegistry;
import io.github.jaymcole.housegraph.discord.SlashCommandSpec;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.resource.Subscription;
import io.github.jaymcole.housegraph.ui.view.NodeContentProvider;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A modular Discord slash command with typed options. Declare a {@code /command} and its
 * options ({@code env, count:integer}); the node grows one named output port per option,
 * plus {@code Channel}, sender, and a {@code Reply} handle. When someone runs the command,
 * it fires its flow-out with each option's value on its matching port.
 * <p>
 * Changing the options {@link #rebuildPorts() rebuilds this node's ports} (edges to
 * surviving options reconnect by name). The command is <em>declared</em> into
 * {@link SlashCommandRegistry} and registered when the bot connects — so set up commands,
 * then connect; a change afterward (options, ephemeral, name) needs a reconnect. Option
 * and command names are lowercased to satisfy Discord.
 */
@Display.Name("Discord Slash Command")
public class DiscordSlashCommandNode extends BaseNode implements NodeContentProvider {

    private static final String DESCRIPTION = "HouseGraph command";

    private final NodeVariable<String> channel = new NodeVariable<>("Channel", String.class);
    private final NodeVariable<String> senderId = new NodeVariable<>("Sender ID", String.class);
    private final NodeVariable<String> senderName = new NodeVariable<>("Sender Name", String.class);
    private final NodeVariable<DiscordReply> reply = new NodeVariable<>("Reply", DiscordReply.class).transientValue();
    private final Map<String, NodeVariable<String>> optionOutputs = new LinkedHashMap<>();
    private final List<CommandOption> options = new ArrayList<>();
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private String resourceName;
    private String command = "command";
    private boolean ephemeral;
    private String declaredBot;
    private String declaredCommand;
    private Subscription subscription;

    @Override
    public void process(ProcessContext ctx) {
        // Outputs are set from the incoming invocation just before execute(); nothing to compute.
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
        // One output per declared option (rebuilt whenever the options change), then the
        // fixed metadata outputs.
        optionOutputs.clear();
        for (CommandOption option : options) {
            NodeVariable<String> output = new NodeVariable<>(option.name(), String.class);
            optionOutputs.put(option.name(), output);
            addOutput(output);
        }
        addOutput(channel);
        addOutput(senderId);
        addOutput(senderName);
        addOutput(reply);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new HashMap<>();
        state.put("command", command);
        state.put("ephemeral", Boolean.toString(ephemeral));
        state.put("options", formatOptions(options));
        if (resourceName != null) {
            state.put("resource", resourceName);
        }
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        String saved = state.get("command");
        if (saved != null && !saved.isBlank()) {
            command = saved;
        }
        ephemeral = Boolean.parseBoolean(state.get("ephemeral"));
        options.clear();
        options.addAll(parseOptions(state.get("options")));
        resourceName = emptyToNull(state.get("resource"));
    }

    @Override
    protected void onActivated() {
        subscribeTo(resourceName);
    }

    @Override
    protected void onRemoved() {
        subscribeTo(null);
    }

    private void subscribeTo(String name) {
        if (subscription != null) {
            subscription.cancel();
            subscription = null;
        }
        resourceName = name;
        redeclare();
        if (name != null) {
            subscription = ResourceRegistry.shared().subscribe(name, payload -> {
                if (payload instanceof DiscordSlashCommand slash) {
                    onCommand(slash);
                }
            });
        }
    }

    /** Withdraws any previous declaration and declares the current command + options (if a bot and name are set). */
    private void redeclare() {
        if (declaredBot != null && declaredCommand != null) {
            SlashCommandRegistry.shared().withdraw(declaredBot, declaredCommand);
        }
        String name = normalizedCommand();
        if (resourceName != null && name != null) {
            declaredBot = resourceName;
            declaredCommand = name;
            SlashCommandRegistry.shared().declare(declaredBot,
                    new SlashCommandSpec(name, DESCRIPTION, ephemeral, new ArrayList<>(options)));
        } else {
            declaredBot = null;
            declaredCommand = null;
        }
    }

    private void onCommand(DiscordSlashCommand slash) {
        String name = normalizedCommand();
        if (name == null || !name.equals(slash.command())) {
            return;
        }
        try {
            execute(() -> {
                for (Map.Entry<String, NodeVariable<String>> option : optionOutputs.entrySet()) {
                    option.getValue().setValue(slash.options().get(option.getKey()));
                }
                channel.setValue(slash.channelId());
                senderId.setValue(slash.authorId());
                senderName.setValue(slash.authorName());
                reply.setValue(slash.reply());
            });
        } catch (IllegalStateException e) {
            // Removed just as the invocation arrived; ignore.
        }
    }

    private String normalizedCommand() {
        if (command == null || command.isBlank()) {
            return null;
        }
        return command.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public Node createNodeContent() {
        TextField commandField = new TextField(command);
        commandField.setPromptText("command (no slash)");
        commandField.textProperty().addListener((obs, old, value) -> {
            command = value;
            redeclare();
        });

        ComboBox<String> botChooser = new ComboBox<>();
        botChooser.setPromptText("From bot…");
        botChooser.setMaxWidth(Double.MAX_VALUE);
        botChooser.getItems().setAll(ResourceRegistry.shared().activeNames());
        if (resourceName != null) {
            botChooser.setValue(resourceName);
        }
        botChooser.setOnShowing(e -> botChooser.getItems().setAll(ResourceRegistry.shared().activeNames()));
        botChooser.setOnAction(e -> subscribeTo(botChooser.getValue()));

        CheckBox ephemeralBox = new CheckBox("Ephemeral reply");
        ephemeralBox.setStyle("-fx-text-fill: #dddddd; -fx-font-size: 11px;");
        ephemeralBox.setSelected(ephemeral);
        ephemeralBox.selectedProperty().addListener((obs, was, now) -> {
            ephemeral = now;
            redeclare();
        });

        // Options are edited as rows and applied all at once, so the node's ports rebuild
        // just once (on Apply) rather than jarringly on every keystroke.
        VBox optionRows = new VBox(3);
        for (CommandOption option : options) {
            optionRows.getChildren().add(optionRow(option));
        }

        Button addButton = new Button("+ Option");
        addButton.setOnAction(e -> optionRows.getChildren()
                .add(optionRow(new CommandOption("option" + (optionRows.getChildren().size() + 1), DiscordOptionType.TEXT))));

        Button applyButton = new Button("Apply");
        applyButton.setOnAction(e -> applyOptionRows(optionRows));

        Label optionsLabel = new Label("Options");
        optionsLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");

        return new VBox(4, commandField, botChooser, ephemeralBox,
                optionsLabel, optionRows, new HBox(6, addButton, applyButton));
    }

    /** One editable option row (name, type, remove); read back on Apply. */
    private HBox optionRow(CommandOption option) {
        TextField name = new TextField(option.name());
        name.setPromptText("name");
        HBox.setHgrow(name, Priority.ALWAYS);

        ComboBox<DiscordOptionType> type = new ComboBox<>();
        type.getItems().setAll(DiscordOptionType.values());
        type.setValue(option.type());

        HBox row = new HBox(4, name, type);
        Button remove = new Button("×");
        remove.setOnAction(e -> ((VBox) row.getParent()).getChildren().remove(row));
        row.getChildren().add(remove);
        return row;
    }

    @SuppressWarnings("unchecked")
    private void applyOptionRows(VBox optionRows) {
        List<CommandOption> edited = new ArrayList<>();
        for (Node rowNode : optionRows.getChildren()) {
            HBox row = (HBox) rowNode;
            String name = ((TextField) row.getChildren().get(0)).getText();
            DiscordOptionType type = ((ComboBox<DiscordOptionType>) row.getChildren().get(1)).getValue();
            String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                edited.add(new CommandOption(normalized, type == null ? DiscordOptionType.TEXT : type));
            }
        }
        if (edited.equals(options)) {
            return; // no change - avoid a needless rebuild
        }
        options.clear();
        options.addAll(edited);
        redeclare();
        rebuildPorts();
    }

    // --- Option text parsing/formatting (e.g. "env, count:integer") ---------------

    private static List<CommandOption> parseOptions(String text) {
        List<CommandOption> parsed = new ArrayList<>();
        if (text == null) {
            return parsed;
        }
        for (String entry : text.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            String name = (colon < 0 ? trimmed : trimmed.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
            DiscordOptionType type = colon < 0 ? DiscordOptionType.TEXT : parseType(trimmed.substring(colon + 1).trim());
            if (!name.isEmpty()) {
                parsed.add(new CommandOption(name, type));
            }
        }
        return parsed;
    }

    private static DiscordOptionType parseType(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "integer", "int", "number" -> DiscordOptionType.INTEGER;
            case "boolean", "bool" -> DiscordOptionType.BOOLEAN;
            case "user" -> DiscordOptionType.USER;
            default -> DiscordOptionType.TEXT;
        };
    }

    private static String formatOptions(List<CommandOption> options) {
        StringBuilder text = new StringBuilder();
        for (CommandOption option : options) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(option.name()).append(':').append(option.type().name().toLowerCase(Locale.ROOT));
        }
        return text.toString();
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
