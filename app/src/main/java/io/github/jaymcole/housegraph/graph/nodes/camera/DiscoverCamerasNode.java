package io.github.jaymcole.housegraph.graph.nodes.camera;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.camera.CameraConfigStore;
import io.github.jaymcole.housegraph.camera.CameraDiscovery;
import io.github.jaymcole.housegraph.camera.DiscoveredCamera;
import io.github.jaymcole.housegraph.camera.OnvifEnrichment;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.ui.view.NodeContentProvider;
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
 * <p>
 * WS-Discovery only reveals a camera's raw scopes; the good values — the clean model and the
 * app-set custom name — need a second, <em>authenticated</em> ONVIF request. So each discovered
 * camera is enriched via {@link OnvifEnrichment}: with a <b>Password</b> supplied it reads the
 * camera's app-set name and clean model, and those authoritative values are merged into the
 * registry. The password is marked secret so it's never written to a save file; wire a Secret
 * Loader into it rather than typing it in. A camera whose ONVIF service can't be reached (or
 * that rejects the login) just keeps its discovery values — one bad camera never fails the sweep.
 */
@Display.Name("Discover Cameras")
public class DiscoverCamerasNode extends BaseNode implements NodeContentProvider {

    /** Per-request timeout for the authenticated ONVIF enrichment calls. */
    private static final int ENRICH_TIMEOUT_SECONDS = 5;

    private final NodeVariable<Integer> timeout = new NodeVariable<>("Timeout (s)", Integer.class, true);
    private final NodeVariable<String> username = new NodeVariable<>("Username", String.class, true);
    private final NodeVariable<String> password = new NodeVariable<>("Password", String.class, true).markSecret();
    private final NodeVariable<Integer> camerasFound = new NodeVariable<>("Cameras Found", Integer.class);
    private final NodeVariable<String> configPath = new NodeVariable<>("Config Path", String.class);
    private final FlowPort flowIn = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort flowOut = new FlowPort("", FlowPort.Direction.OUT);

    private Label statusLabel;
    private String lastStatus;

    public DiscoverCamerasNode() {
        // Pre-fill the common case so a deep-config sweep works after just supplying a password.
        username.setValue("admin");
    }

    @Override
    public void process(ProcessContext ctx) {
        List<DiscoveredCamera> cameras = CameraDiscovery.discover(timeoutSeconds());
        enrichCameras(cameras);
        CameraConfigStore.MergeResult result = CameraConfigStore.merge(cameras);
        camerasFound.setValue(cameras.size());
        configPath.setValue(CameraConfigStore.defaultPath().toString());
        lastStatus = cameras.size() + " found — " + result.added() + " new, " + result.updated() + " updated"
                + (result.skipped() > 0 ? ", " + result.skipped() + " without MAC" : "");
    }

    /**
     * Second pass: enrich each camera with its authenticated ONVIF details (clean model, and —
     * with a password — the app-set name), replacing the discovery entry in-place. A camera
     * whose ONVIF service can't be reached is left as its discovery result.
     */
    private void enrichCameras(List<DiscoveredCamera> cameras) {
        String user = username.getValue();
        String pass = password.getValue();
        for (int i = 0; i < cameras.size(); i++) {
            cameras.set(i, OnvifEnrichment.enrich(cameras.get(i), user, pass, ENRICH_TIMEOUT_SECONDS));
        }
    }

    private int timeoutSeconds() {
        Integer value = timeout.getValue();
        return value == null || value <= 0 ? 4 : value;
    }

    @Override
    public void configureInputs() {
        addInput(timeout);
        addInput(username);
        addInput(password);
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

    /**
     * An entry point despite having a flow input: the "Discover cameras" button calls
     * {@link #execute()} directly, so its {@link io.github.jaymcole.housegraph.graph.ExecutionPolicy}
     * is meaningful. The structural default would miss this (it has a flow-in), so it's declared here.
     */
    @Override
    public boolean isExecutionEntryPoint() {
        return true;
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
