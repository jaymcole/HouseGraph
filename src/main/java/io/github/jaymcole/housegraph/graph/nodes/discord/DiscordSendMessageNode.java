package io.github.jaymcole.housegraph.graph.nodes.discord;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.discord.DiscordBot;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.ui.NodeContentProvider;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.Map;

/**
 * Posts a message to a Discord channel when triggered — the action side of the pattern.
 * It looks up its bot by name from the resource registry (rather than being wired to it)
 * and sends its {@code Message} to the channel given by its {@code Channel} input. Both
 * inputs can be typed in place or wired: wire a command node's {@code Channel} output in
 * to reply in the channel the command came from, or type a fixed channel id for a set
 * destination. Control flows through, so you can chain more work after sending.
 */
@Display.Name("Discord Send Message")
public class DiscordSendMessageNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<String> message = new NodeVariable<>("Message", String.class, true);
    private final NodeVariable<String> channel = new NodeVariable<>("Channel", String.class, true);
    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private String resourceName;

    @Override
    public void process() {
        String text = message.getValue();
        String channelId = channel.getValue();
        if (resourceName == null || channelId == null || channelId.isBlank() || text == null) {
            return;
        }
        ResourceRegistry.shared().find(resourceName, DiscordBot.class)
                .ifPresent(bot -> bot.sendMessage(channelId, text));
    }

    @Override
    public void configureInputs() {
        addInput(message);
        addInput(channel);
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new HashMap<>();
        if (resourceName != null) {
            state.put("resource", resourceName);
        }
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        resourceName = emptyToNull(state.get("resource"));
    }

    @Override
    public Node createNodeContent() {
        ComboBox<String> botChooser = new ComboBox<>();
        botChooser.setPromptText("To bot…");
        botChooser.setMaxWidth(Double.MAX_VALUE);
        botChooser.getItems().setAll(ResourceRegistry.shared().activeNames());
        if (resourceName != null) {
            botChooser.setValue(resourceName);
        }
        botChooser.setOnShowing(e -> botChooser.getItems().setAll(ResourceRegistry.shared().activeNames()));
        botChooser.setOnAction(e -> resourceName = botChooser.getValue());

        return new VBox(4, botChooser);
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
