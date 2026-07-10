package io.github.jaymcole.housegraph.graph.nodes.resource;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.resource.Subscription;
import io.github.jaymcole.housegraph.ui.view.NodeContentProvider;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;

import java.util.Map;

/**
 * The event-source counterpart to {@link EchoResourceNode}: it listens to a named
 * resource and, each time that resource publishes an event, drives graph execution
 * from here — setting its {@code Message} output to the payload and firing its flow-out,
 * exactly like a Trigger but fired externally.
 * <p>
 * This is the modularity mechanism from the design discussion: each listener is a
 * self-contained, external trigger point on the graph (a Discord <em>command</em> node
 * would work identically — same subscription + {@code execute()} on an incoming event).
 * The resource is referenced by name (survives save/load via node state), so the
 * listener works no matter where it sits relative to the resource.
 */
@Display.Name("Echo Listener")
public class EchoListenerNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<String> message = new NodeVariable<>("Message", String.class);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private String resourceName;
    private Subscription subscription;

    @Override
    public void process() {
        // The value is set from the incoming event just before execute() is called;
        // there's nothing to compute here.
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
        addOutput(message);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }

    @Override
    public Map<String, String> saveState() {
        return resourceName == null ? Map.of() : Map.of("resource", resourceName);
    }

    @Override
    public void loadState(Map<String, String> state) {
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

    /** Points this listener at {@code name} (or nothing), cancelling any previous subscription. */
    private void subscribeTo(String name) {
        if (subscription != null) {
            subscription.cancel();
            subscription = null;
        }
        resourceName = name;
        if (name != null) {
            subscription = ResourceRegistry.shared().subscribe(name, payload -> {
                if (payload instanceof String text) {
                    onEvent(text);
                }
            });
        }
    }

    private void onEvent(String payload) {
        // The payload is captured here and applied inside the pass (on the execution
        // thread), so rapid events each drive their own pass without clobbering.
        execute(() -> message.setValue(payload));
    }

    @Override
    public Node createNodeContent() {
        ComboBox<String> chooser = new ComboBox<>();
        chooser.setPromptText("Listen to…");
        chooser.setMaxWidth(Double.MAX_VALUE);
        chooser.getItems().setAll(ResourceRegistry.shared().activeNames());
        if (resourceName != null) {
            chooser.setValue(resourceName);
        }
        // Available resources change as they start/stop; refresh right before showing.
        chooser.setOnShowing(e -> chooser.getItems().setAll(ResourceRegistry.shared().activeNames()));
        chooser.setOnAction(e -> subscribeTo(chooser.getValue()));
        return chooser;
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
