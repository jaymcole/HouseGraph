package io.github.jaymcole.housegraph.graph.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for a node whose type this build can't resolve — because the library providing it isn't
 * installed, is disabled, or has been removed — and <b>preserves it exactly</b> so that opening and
 * re-saving a graph doesn't destroy work.
 *
 * <h2>Why this exists</h2>
 * An unresolvable node used to load as a {@code null} slot in the snapshot, which held its index so
 * later nodes stayed aligned, but never reached the canvas and so was never written back out. The
 * consequence was severe and silent: open a graph whose node type you no longer have, press Quick
 * Save, and that node — its position, its authored values, its saved state, and every edge touching
 * it — was gone for good. With node libraries moving out of this repository that stops being an
 * exotic case and becomes the normal one, so the placeholder has to round-trip instead.
 *
 * <h2>How it preserves the node</h2>
 * It keeps the node's original save-file JSON verbatim and {@code GraphFileIO} re-emits that blob on
 * save, overwriting only {@code x}/{@code y} in case the user moved it. Re-deriving the JSON from
 * this placeholder's reconstructed ports would quietly drop everything this build doesn't model —
 * the {@code state} map, {@code maxConcurrency}, {@code timeoutMillis}, {@code requiredInputs}, and
 * any key a future format adds. Copying the blob preserves all of it unconditionally.
 * <p>
 * Its ports are rebuilt from the saved {@code inputs}/{@code outputs} arrays, keeping the original
 * names, so edges that reference an endpoint <em>by name</em> still resolve onto it. Flow ports
 * aren't persisted per node — only referenced by edges — so {@code GraphFileIO} back-fills those
 * with {@link #ensureFlowPort} once it has read the edge lists.
 *
 * <p>Being a real {@code BaseNode} rather than a nullable field on the snapshot record is what makes
 * every existing path work unchanged: the canvas places it, the view renders it, edges wire to it,
 * and selection and dragging apply.
 *
 * <p>{@code @Node.Disabled} keeps it out of the Add-Node menu — you can't usefully add one on
 * purpose — while leaving it a normal, resolvable class.
 */
@Node.Disabled("Placeholder for a node type this build can't resolve")
@Display.Name("Missing Node")
public class MissingNode extends BaseNode implements NodeContentProvider {

    private JSONObject rawJson = new JSONObject();
    private JSONObject rawPluginRow;
    private String missingType = "unknown";
    private String missingPluginId;
    private String missingPluginName;
    private String missingRepository;

    /**
     * Builds a placeholder for one save-file node entry.
     *
     * @param nodeJson    the node's entry exactly as it appeared in the file; retained and re-emitted
     * @param pluginEntry that node's row from the file's root {@code plugins} table, or null
     * @return the placeholder, with its data ports already rebuilt from {@code nodeJson}
     */
    public static MissingNode from(JSONObject nodeJson, JSONObject pluginEntry) {
        MissingNode node = new MissingNode();
        // Deep copy via the text form: the caller's JSONObject belongs to the parsed file and must
        // not be able to change underneath us, nor us under it.
        node.rawJson = new JSONObject(nodeJson.toString());
        node.missingType = nodeJson.optString("type", "unknown");
        node.missingPluginId = nodeJson.optString("plugin", null);
        if (pluginEntry != null) {
            node.rawPluginRow = new JSONObject(pluginEntry.toString());
            node.missingPluginName = pluginEntry.optString("name", null);
            node.missingRepository = pluginEntry.optString("repository", null);
        }
        return node;
    }

    /** The node's save-file JSON, to be written back out unchanged apart from its position. */
    public JSONObject rawJson() {
        return rawJson;
    }

    /**
     * This node's row from the save file's root {@code plugins} table, or null if there wasn't one.
     * Retained and re-emitted for the same reason as {@link #rawJson()}: the library isn't installed,
     * so its name, version and — most importantly — the repository it can be installed from exist
     * nowhere else on this machine. Dropping the row would leave the user holding a graph that names
     * a node type with no way to find out where it comes from.
     */
    public JSONObject rawPluginRow() {
        return rawPluginRow;
    }

    /** The unresolvable type id, as recorded in the save file. */
    public String missingType() {
        return missingType;
    }

    /** The library the save file says provides this type, or null if it didn't record one. */
    public String missingPluginId() {
        return missingPluginId;
    }

    /** Where that library can be installed from, or null if the save file didn't record it. */
    public String missingRepository() {
        return missingRepository;
    }

    @Override
    public String getName() {
        return "Missing: " + missingType;
    }

    @Override
    public void configureInputs() {
        rebuildFrom("inputs", true);
    }

    @Override
    public void configureOutputs() {
        rebuildFrom("outputs", false);
    }

    private void rebuildFrom(String key, boolean input) {
        JSONArray saved = rawJson.optJSONArray(key);
        if (saved == null) {
            return;
        }
        for (int i = 0; i < saved.length(); i++) {
            JSONObject entry = saved.optJSONObject(i);
            String name = entry == null ? "" : entry.optString("name", "");
            // Typed as String regardless of what the real node declared: the placeholder never runs,
            // and a value it can't interpret is preserved in the raw JSON rather than on the port.
            NodeVariable<String> variable = new NodeVariable<>(name, String.class);
            if (input) {
                addInput(variable);
            } else {
                addOutput(variable);
            }
        }
    }

    /**
     * Adds a data port matching an edge endpoint that the saved value arrays didn't account for.
     * A no-op when the reference already resolves.
     *
     * @param ref    the edge's saved endpoint: a port name, or a positional index
     * @param output true for an output port, false for an input
     */
    public void ensureDataPort(Object ref, boolean output) {
        List<NodeVariable> ports = output ? getOutputs() : getInputs();
        if (ref instanceof Number number) {
            for (int i = ports.size(); i <= number.intValue(); i++) {
                addPort(new NodeVariable<>("", String.class), output);
            }
            return;
        }
        if (ref instanceof String name && !hasVariable(ports, name)) {
            addPort(new NodeVariable<>(name, String.class), output);
        }
    }

    /**
     * Adds a flow port matching an edge endpoint. Flow ports are never persisted on the node itself,
     * only referenced by edges, so this is the only way a placeholder learns it had any.
     *
     * @param ref the edge's saved endpoint: a port name, or a positional index
     * @param out true for an outgoing flow port, false for an incoming one
     */
    public void ensureFlowPort(Object ref, boolean out) {
        FlowPort.Direction direction = out ? FlowPort.Direction.OUT : FlowPort.Direction.IN;
        List<FlowPort> ports = out ? getFlowOutputs() : getFlowInputs();
        if (ref == null) {
            ref = 0;
        }
        if (ref instanceof Number number) {
            for (int i = ports.size(); i <= number.intValue(); i++) {
                addFlowPort(new FlowPort("", direction), out);
            }
            return;
        }
        if (ref instanceof String name && !hasFlowPort(ports, name)) {
            addFlowPort(new FlowPort(name, direction), out);
        }
    }

    private void addPort(NodeVariable<String> variable, boolean output) {
        if (output) {
            addOutput(variable);
        } else {
            addInput(variable);
        }
    }

    private void addFlowPort(FlowPort port, boolean out) {
        if (out) {
            addFlowOutput(port);
        } else {
            addFlowInput(port);
        }
    }

    @SuppressWarnings("rawtypes")
    private static boolean hasVariable(List<NodeVariable> ports, String name) {
        for (NodeVariable port : ports) {
            if (port.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFlowPort(List<FlowPort> ports, String name) {
        for (FlowPort port : ports) {
            if (port.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Always misconfigured — reusing the existing visual treatment for a node that can't run. */
    @Override
    public boolean isMisconfigured() {
        return true;
    }

    /**
     * Always fails, loudly. A placeholder that quietly did nothing would let a graph appear to run
     * correctly while silently skipping whatever the real node did.
     */
    @Override
    public void process(ProcessContext ctx) {
        throw new IllegalStateException("Node type \"" + missingType + "\" is not installed"
                + (missingPluginId == null ? "" : " (provided by \"" + missingPluginId + "\")"));
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        Label headline = new Label("Type \"" + missingType + "\" is not installed");
        headline.setWrapText(true);
        headline.setStyle("-fx-text-fill: #ff6b6b; -fx-font-weight: bold;");

        VBox box = new VBox(4, headline);
        box.setPadding(new Insets(4, 0, 0, 0));

        String provider = missingPluginName != null ? missingPluginName : missingPluginId;
        if (provider != null) {
            Label from = new Label("Provided by " + provider);
            from.setStyle("-fx-text-fill: #b0b0b0;");
            box.getChildren().add(from);
        }
        if (missingRepository != null) {
            Label where = new Label(missingRepository);
            where.setWrapText(true);
            where.setStyle("-fx-text-fill: #b0b0b0; -fx-font-size: 10px;");
            box.getChildren().add(where);
        }

        Label reassurance = new Label("Kept as-is; installing the library restores it.");
        reassurance.setWrapText(true);
        reassurance.setStyle("-fx-text-fill: #808080; -fx-font-size: 10px;");
        box.getChildren().add(reassurance);
        return box;
    }

    /** The saved values are carried in the raw JSON, so there is nothing extra to persist here. */
    @Override
    public java.util.Map<String, String> saveState() {
        return java.util.Map.of();
    }

    /** Every port this placeholder carries, for tests and for the canvas to reason about. */
    public List<String> dataPortNames(boolean output) {
        List<String> names = new ArrayList<>();
        for (NodeVariable variable : output ? getOutputs() : getInputs()) {
            names.add(variable.name);
        }
        return names;
    }
}
