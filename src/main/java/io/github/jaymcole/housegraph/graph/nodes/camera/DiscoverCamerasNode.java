package io.github.jaymcole.housegraph.graph.nodes.camera;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.camera.CameraConfigStore;
import io.github.jaymcole.housegraph.camera.CameraDiscovery;
import io.github.jaymcole.housegraph.camera.DiscoveredCamera;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.ui.NodeContentProvider;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * A convenience tool node: discovers Reolink/ONVIF cameras on the local network and
 * merges them into the camera registry ({@link CameraConfigStore}). You run it (its
 * button, or a flow trigger) only when your camera setup changes — the useful output is
 * the config file it writes, keyed by each camera's MAC.
 * <p>
 * Discovery is slow (a multicast wait, and a port-scan fallback), so it runs on the
 * background execution thread rather than freezing the UI. Outputs the number found and
 * the config path; control flows through so you can chain after it.
 */
@Display.Name("Discover Cameras")
public class DiscoverCamerasNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<Integer> timeout = new NodeVariable<>("Timeout (s)", Integer.class, true);
    private final NodeVariable<Integer> camerasFound = new NodeVariable<>("Cameras Found", Integer.class);
    private final NodeVariable<String> configPath = new NodeVariable<>("Config Path", String.class);
    private final FlowPort flowIn = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort flowOut = new FlowPort("", FlowPort.Direction.OUT);

    private Label statusLabel;
    private String lastStatus;

    @Override
    public void process() {
        List<DiscoveredCamera> cameras = CameraDiscovery.discover(timeoutSeconds());
        CameraConfigStore.MergeResult result = CameraConfigStore.merge(cameras);
        camerasFound.setValue(cameras.size());
        configPath.setValue(CameraConfigStore.defaultPath().toString());
        lastStatus = cameras.size() + " found — " + result.added() + " new, " + result.updated() + " updated"
                + (result.skipped() > 0 ? ", " + result.skipped() + " without MAC" : "");
    }

    private int timeoutSeconds() {
        Integer value = timeout.getValue();
        return value == null || value <= 0 ? 4 : value;
    }

    @Override
    public void configureInputs() {
        addInput(timeout);
    }

    @Override
    public void configureOutputs() {
        addOutput(camerasFound);
        addOutput(configPath);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(flowIn);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(flowOut);
    }

    @Override
    protected void onExecuted() {
        if (statusLabel == null) {
            return;
        }
        Throwable error = getLastError();
        if (error != null) {
            statusLabel.setText("Error: " + error.getMessage());
        } else if (lastStatus != null) {
            statusLabel.setText(lastStatus);
        }
    }

    @Override
    public Node createNodeContent() {
        Button discoverButton = new Button("Discover cameras");
        discoverButton.setMaxWidth(Double.MAX_VALUE);
        discoverButton.setOnAction(event -> {
            statusLabel.setText("Discovering…");
            execute();
        });

        statusLabel = new Label("Not run yet");
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(200);

        return new VBox(4, discoverButton, statusLabel);
    }
}
