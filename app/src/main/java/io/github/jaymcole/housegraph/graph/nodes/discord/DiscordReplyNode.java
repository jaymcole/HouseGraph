package io.github.jaymcole.housegraph.graph.nodes.discord;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.discord.DiscordReply;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;

/**
 * Answers a slash-command invocation. Wire a Discord Slash Command node's {@code Reply}
 * output into this node's {@code Reply} input and give it a {@code Message}; when
 * triggered, it sends that text back to the interaction (editing the deferred
 * "thinking…" response). This is the slash counterpart to Send Message — the reply goes
 * to the specific invocation, so no channel is needed. Control flows through.
 */
@Display.Name("Discord Reply")
public class DiscordReplyNode extends BaseNode {

    private final NodeVariable<DiscordReply> reply = new NodeVariable<>("Reply", DiscordReply.class).transientValue().required();
    private final NodeVariable<String> message = new NodeVariable<>("Message", String.class, true).required();
    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        DiscordReply handle = reply.getValue();
        String text = message.getValue();
        if (handle != null && text != null) {
            handle.reply(text);
        }
    }

    @Override
    public void configureInputs() {
        addInput(reply);
        addInput(message);
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
}
