package io.github.jaymcole.housegraph.graph.nodes.camera;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.camera.ReolinkClient;
import io.github.jaymcole.housegraph.camera.ReolinkClient.DetectionState;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;

/**
 * Polls a Reolink camera for what it's currently detecting and exposes the result as a
 * single <b>Status</b> group — one of a fixed set: {@code human}, {@code vehicle},
 * {@code animal}, {@code motion}, or {@code none} (see {@link DetectionState#topStatus()}).
 * On AI-capable cameras this comes from Reolink's {@code GetAiState} categories; older
 * cameras fall back to plain {@code GetMdState} motion.
 * <p>
 * This is a read/sensor node: it pulls fresh state each time it's triggered, so wire a
 * repeating trigger into it to keep a status live, and chain its Status/Detected outputs
 * into an If, a Discord message, or the squirrel-alarm sign. If the camera can't be
 * reached or rejects the login, process() fails for that pass (surfaced as the node's
 * error) rather than reporting a stale or false "none".
 * <p>
 * The Password input is marked secret so it's never written to a save file; rather than
 * typing it in, wire a Secret Loader into it so the credential stays in the encrypted
 * secrets store. The Host is the camera's IP or hostname; prefix it with {@code https://}
 * for a camera reachable only over TLS.
 */
@Display.Name("Camera Motion Status")
public class CameraMotionStatusNode extends BaseNode {

    private final NodeVariable<String> host = new NodeVariable<>("Host", String.class, true);
    private final NodeVariable<String> username = new NodeVariable<>("Username", String.class, true);
    private final NodeVariable<String> password = new NodeVariable<>("Password", String.class, true).markSecret();
    private final NodeVariable<Integer> channel = new NodeVariable<>("Channel", Integer.class, true);

    private final NodeVariable<String> status = new NodeVariable<>("Status", String.class);
    private final NodeVariable<Float> detected = new NodeVariable<>("Detected", Float.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    public CameraMotionStatusNode() {
        // Pre-fill the common case so the node works after just entering host + password.
        username.setValue("admin");
    }

    @Override
    public void process() {
        DetectionState state = ReolinkClient.poll(
                host.getValue(),
                username.getValue(),
                password.getValue(),
                channelValue(),
                5);
        status.setValue(state.topStatus());
        detected.setValue(state.anyDetected() ? 1f : 0f);
    }

    private int channelValue() {
        Integer value = channel.getValue();
        return value == null || value < 0 ? 0 : value;
    }

    @Override
    public void configureInputs() {
        addInput(host);
        addInput(username);
        addInput(password);
        addInput(channel);
    }

    @Override
    public void configureOutputs() {
        addOutput(status);
        addOutput(detected);
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
