package io.github.jaymcole.housegraph.graph.nodes.camera;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.camera.ReolinkClient;
import io.github.jaymcole.housegraph.camera.ReolinkClient.DetectionState;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.ui.NodeContentProvider;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.Map;

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
 * The camera is picked from a dropdown of those known to the registry (populate it with
 * Discover Cameras); the node starts from that camera's last known IP and, if it can't be
 * reached there, rediscovers it by MAC and updates the cached address — see
 * {@link CameraSelector}. The Password input is marked secret so it's never written to a
 * save file; rather than typing it in, wire a Secret Loader into it so the credential stays
 * in the encrypted secrets store.
 */
@Display.Name("Camera Motion Status")
public class CameraMotionStatusNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<String> username = new NodeVariable<>("Username", String.class, true);
    private final NodeVariable<String> password = new NodeVariable<>("Password", String.class, true).markSecret();
    private final NodeVariable<Integer> channel = new NodeVariable<>("Channel", Integer.class, true);

    private final NodeVariable<Boolean> motion = new NodeVariable<>("motion", Boolean.class);
    private final NodeVariable<DetectionState> detectionState = new NodeVariable<>("state", DetectionState.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private final CameraSelector camera = new CameraSelector();

    public CameraMotionStatusNode() {
        // Pre-fill the common case so the node works after just picking a camera + password.
        username.setValue("admin");
    }

    @Override
    public void process() {
        DetectionState state = camera.withHost(host ->
                ReolinkClient.poll(host, username.getValue(), password.getValue(), channelValue(), 5));
        detectionState.setValue(state);
        motion.setValue(state.anyDetected());
    }

    private int channelValue() {
        Integer value = channel.getValue();
        return value == null || value < 0 ? 0 : value;
    }

    @Override
    public void configureInputs() {
        addInput(username);
        addInput(password);
        addInput(channel);
    }

    @Override
    public void configureOutputs() {
        addOutput(motion);
        addOutput(detectionState);
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
        return camera.saveState();
    }

    @Override
    public void loadState(Map<String, String> state) {
        camera.loadState(state);
    }

    @Override
    public Node createNodeContent() {
        Label label = new Label("Camera");
        label.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");
        VBox box = new VBox(2, label, camera.buildComboBox());
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }
}
