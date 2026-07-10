package io.github.jaymcole.housegraph.graph.nodes.camera;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.camera.ReolinkClient;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.ui.view.NodeContentProvider;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.util.Map;

/**
 * Grabs a single still frame from a Reolink camera and exposes it as an <b>Image</b> output.
 * Like {@link CameraMotionStatusNode} this is a read/sensor node: it pulls a fresh frame each
 * time it's triggered, so wire a repeating trigger (or an event) into it to keep the frame
 * live, and chain its Image output into an Image Viewer, or downstream to whatever consumes
 * a frame. If the camera can't be reached or rejects the login, process() fails for that pass
 * (surfaced as the node's error) rather than emitting a stale frame.
 * <p>
 * The camera is picked from a dropdown of those known to the registry (populate it with
 * Discover Cameras); the node starts from that camera's last known IP and, if it can't be
 * reached there, rediscovers it by MAC and updates the cached address — see
 * {@link CameraSelector}. The Password input is marked secret so it's never written to a
 * save file; rather than typing it in, wire a Secret Loader into it so the credential stays
 * in the encrypted secrets store.
 */
@Display.Name("Camera Snapshot")
public class CameraSnapshotNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<String> username = new NodeVariable<>("Username", String.class, true);
    private final NodeVariable<String> password = new NodeVariable<>("Password", String.class, true).markSecret().required();
    private final NodeVariable<Integer> channel = new NodeVariable<>("Channel", Integer.class, true);

    private final NodeVariable<Image> frame = new NodeVariable<>("frame", Image.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private final CameraSelector camera = new CameraSelector();

    public CameraSnapshotNode() {
        // Pre-fill the common case so the node works after just picking a camera + password.
        username.setValue("admin");
    }

    @Override
    public void process() {
        byte[] jpeg = camera.withHost(host ->
                ReolinkClient.snapshot(host, username.getValue(), password.getValue(), channelValue(), 5));
        frame.setValue(new Image(new ByteArrayInputStream(jpeg)));
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
        addOutput(frame);
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
