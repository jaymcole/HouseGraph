package io.github.jaymcole.housegraph.graph.nodes.object;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ObjectProperties;
import io.github.jaymcole.housegraph.graph.ObjectProperties.Property;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Takes any object on a single {@code Object} input and exposes each of its properties as
 * its own typed output. The outputs aren't fixed: they're derived from the declared type
 * of whatever is wired into the input, via {@link ObjectProperties}. Connect a source and
 * the node grows one output per property; disconnect it (or wire a differently-typed
 * source) and the outputs — and any downstream edges reading them — change to match.
 * <p>
 * The reaction is driven by {@link #onInputEdgeAdded}/{@link #onInputEdgeRemoved}, the
 * graph's edge-wiring hooks. The derived property list is persisted via
 * {@link #saveState()}/{@link #loadState(Map)}, so the outputs rebuild on load independently
 * of the order edges are recreated in.
 */
@Display.Name("Object Decomposer")
public class ObjectDecomposerNode extends BaseNode {

    private static final Logger log = Log.get(ObjectDecomposerNode.class);

    private final NodeVariable<Object> objectInput = new NodeVariable<>("Object", Object.class).required();
    private final Map<String, NodeVariable> outputsByProperty = new LinkedHashMap<>();

    /** The current output shape - one entry per exposed property, in port order. */
    private List<Property> properties = new ArrayList<>();

    /** Guards against reacting to the edge churn our own {@link #rebuildPorts()} triggers. */
    private boolean refreshing;

    @Override
    public void process(ProcessContext ctx) {
        Object value = objectInput.getValue();
        for (Map.Entry<String, NodeVariable> entry : outputsByProperty.entrySet()) {
            entry.getValue().setValue(value == null ? null : ObjectProperties.read(value, entry.getKey()));
        }
    }

    @Override
    public void configureInputs() {
        addInput(objectInput);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void configureOutputs() {
        outputsByProperty.clear();
        for (Property property : properties) {
            NodeVariable<?> output = new NodeVariable<>(property.name(), property.type());
            outputsByProperty.put(property.name(), output);
            addOutput(output);
        }
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(new FlowPort("", FlowPort.Direction.IN));
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(new FlowPort("", FlowPort.Direction.OUT));
    }

    @Override
    protected void onInputEdgeAdded(Edge edge) {
        refreshOutputs();
    }

    @Override
    protected void onInputEdgeRemoved(Edge edge) {
        refreshOutputs();
    }

    /**
     * Recomputes the output ports from whatever is <em>currently</em> wired into the input
     * and, if they actually changed, rebuilds the node. The source type is read from the
     * live graph state (not a hook's edge argument), so it stays correct even though the
     * hooks arrive asynchronously and {@link #rebuildPorts()} briefly deletes and recreates
     * the input edge — the recompute simply sees the settled wiring. The unchanged-list
     * early return keeps this idempotent, so that churn resolves to a no-op instead of
     * looping.
     */
    private void refreshOutputs() {
        if (refreshing) {
            return;
        }
        Class<?> sourceType = connectedSourceType();
        List<Property> desired = sourceType == null ? List.of() : ObjectProperties.of(sourceType);
        if (desired.equals(properties)) {
            return;
        }
        properties = new ArrayList<>(desired);
        refreshing = true;
        try {
            rebuildPorts();
        } finally {
            refreshing = false;
        }
    }

    /** Declared type of the source currently feeding {@link #objectInput}, or null if nothing is wired in. */
    private Class<?> connectedSourceType() {
        for (Edge edge : getIncomingDataEdges()) {
            if (edge.getTargetVariable() == objectInput) {
                return edge.getSourceVariable().type;
            }
        }
        return null;
    }

    @Override
    public Map<String, String> saveState() {
        if (properties.isEmpty()) {
            return Map.of();
        }
        StringBuilder text = new StringBuilder();
        for (Property property : properties) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(property.name()).append(':').append(property.type().getName());
        }
        Map<String, String> state = new HashMap<>();
        state.put("properties", text.toString());
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        properties = parseProperties(state.get("properties"));
    }

    /** Parses the {@code "name:fqcn, ..."} form written by {@link #saveState()}; drops any entry whose type won't resolve. */
    private static List<Property> parseProperties(String text) {
        List<Property> parsed = new ArrayList<>();
        if (text == null) {
            return parsed;
        }
        for (String entry : text.split(",")) {
            String trimmed = entry.trim();
            int colon = trimmed.lastIndexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = trimmed.substring(0, colon).trim();
            Class<?> type = resolveType(trimmed.substring(colon + 1).trim());
            if (!name.isEmpty() && type != null) {
                parsed.add(new Property(name, type));
            }
        }
        return parsed;
    }

    private static Class<?> resolveType(String className) {
        try {
            return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            log.warn("Object Decomposer: dropping property of unknown type {}", className);
            return null;
        }
    }
}
