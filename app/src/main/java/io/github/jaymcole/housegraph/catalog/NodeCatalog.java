package io.github.jaymcole.housegraph.catalog;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeMetadata;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.plugin.PluginDirectory;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The machine-readable description of every node type a {@link NodeRegistry} can currently offer:
 * its id, where it sits, what library owns it, and its typed inputs, outputs and flow ports —
 * everything an external harness needs to generate or validate a graph without opening the editor.
 *
 * <h2>Why this instantiates every node type</h2>
 * {@link NodeMetadata} deliberately reads only what is visible by reflection on an uninitialised
 * {@link Class}, because {@code NodeRegistry}'s own index is rebuilt implicitly (on first search,
 * on every install/remove) and must never run a library's static initializers or risk an expensive
 * or throwing constructor as a side effect of something incidental. A node's <em>ports</em>,
 * though, are built lazily and need a real instance to read (see {@code BaseNode.configureInputs}).
 * <p>
 * This class accepts that cost because building a catalog is always an explicit, on-demand act —
 * a CLI invocation, never something that happens implicitly as a side effect of opening a menu or
 * typing in a search box. Each node type is instantiated in isolation and wrapped in
 * {@code catch (Throwable)}: a plugin's failing static initializer raises an {@link Error}, not an
 * {@link Exception}, and one broken node type must not take the rest of the catalog down with it.
 * A type that fails to instantiate is still listed, with {@link Entry#error()} explaining why and
 * every shape-dependent field left empty.
 *
 * @see NodeSignature
 */
public final class NodeCatalog {

    private static final Logger log = Log.get(NodeCatalog.class);

    /**
     * The catalog's own format version, independent of the app's version — bump this when a field
     * is renamed or removed (an addition alone does not need to), so a harness parsing the JSON can
     * tell whether it understands the shape it received.
     */
    public static final int CATALOG_VERSION = 1;

    private NodeCatalog() {
    }

    /** One port: a data input or output. */
    public record Port(String name, String type, boolean required, boolean secret, boolean manuallyEditable) {

        JSONObject toJson() {
            return new JSONObject()
                    .put("name", name)
                    .put("type", type)
                    .put("required", required)
                    .put("secret", secret)
                    .put("manuallyEditable", manuallyEditable);
        }
    }

    /** One discovered node type, described as fully as reflection plus one instantiation allows. */
    public record Entry(String type,
                        String displayName,
                        String category,
                        String kind,
                        String description,
                        List<String> keywords,
                        String pluginId,
                        String pluginName,
                        String pluginVersion,
                        String pluginRepository,
                        List<Port> inputs,
                        List<Port> outputs,
                        List<String> flowInputs,
                        List<String> flowOutputs,
                        boolean executionEntryPoint,
                        Map<String, String> defaultState,
                        String signature,
                        String error) {

        public Entry {
            keywords = List.copyOf(keywords);
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
            flowInputs = List.copyOf(flowInputs);
            flowOutputs = List.copyOf(flowOutputs);
            defaultState = Map.copyOf(defaultState);
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("type", type);
            json.put("displayName", displayName);
            json.put("category", category);
            json.put("kind", kind == null ? JSONObject.NULL : kind);
            json.put("description", description);
            json.put("keywords", new JSONArray(keywords));
            json.put("library", new JSONObject()
                    .put("id", pluginId)
                    .put("name", pluginName == null ? JSONObject.NULL : pluginName)
                    .put("version", pluginVersion == null ? JSONObject.NULL : pluginVersion)
                    .put("repository", pluginRepository == null ? JSONObject.NULL : pluginRepository));
            if (error != null) {
                json.put("error", error);
                return json;
            }
            json.put("inputs", ports(inputs));
            json.put("outputs", ports(outputs));
            json.put("flowInputs", new JSONArray(flowInputs));
            json.put("flowOutputs", new JSONArray(flowOutputs));
            json.put("executionEntryPoint", executionEntryPoint);
            json.put("defaultState", new JSONObject(new TreeMap<>(defaultState)));
            json.put("signature", signature);
            return json;
        }

        private static JSONArray ports(List<Port> ports) {
            JSONArray array = new JSONArray();
            ports.forEach(port -> array.put(port.toJson()));
            return array;
        }
    }

    /**
     * Describes every node type {@code registry} can discover, sorted by category then type id.
     *
     * @param registry the registry to enumerate — its {@code discover()} already excludes
     *                  {@code @Node.Disabled} types
     * @param plugins  resolves each entry's owning library to a name/version/repository; pass
     *                  {@link PluginDirectory#EMPTY} for bare ids
     * @return one entry per discoverable node type
     */
    public static List<Entry> discover(NodeRegistry registry, PluginDirectory plugins) {
        List<Entry> entries = new ArrayList<>();
        for (NodeRegistry.Entry found : registry.discover()) {
            entries.add(describe(found, plugins));
        }
        entries.sort(Comparator.comparing(Entry::category).thenComparing(Entry::type));
        return entries;
    }

    /** Wraps a list of entries in the versioned root object {@link #discover} produces the rows for. */
    public static JSONObject toJson(List<Entry> entries) {
        JSONObject root = new JSONObject();
        root.put("catalogVersion", CATALOG_VERSION);
        JSONArray nodes = new JSONArray();
        entries.forEach(entry -> nodes.put(entry.toJson()));
        root.put("nodes", nodes);
        return root;
    }

    private static Entry describe(NodeRegistry.Entry found, PluginDirectory plugins) {
        Class<? extends BaseNode> nodeClass = found.nodeClass();
        String type = NodeRegistry.persistentTypeId(nodeClass);
        NodeMetadata metadata = NodeMetadata.of(nodeClass);
        String kind = metadata.kind() == null ? null : metadata.kind().name();
        String pluginId = found.pluginId();
        String pluginName = pluginId;
        String pluginVersion = null;
        String pluginRepository = null;
        if (!NodeRegistry.CORE_PLUGIN_ID.equals(pluginId)) {
            PluginCatalog.Installed installed = plugins.byId(pluginId).orElse(null);
            if (installed != null) {
                pluginName = installed.name();
                pluginVersion = installed.version();
                pluginRepository = installed.repository();
            }
        }

        try {
            BaseNode instance = NodeRegistry.instantiate(nodeClass);
            if (instance == null) {
                return errorEntry(type, found, metadata, kind, pluginId, pluginName, pluginVersion,
                        pluginRepository, "failed to instantiate; see the log for the cause");
            }
            List<Port> inputs = portsOf(instance.getInputs());
            List<Port> outputs = portsOf(instance.getOutputs());
            List<String> flowInputs = flowNamesOf(instance.getFlowInputs());
            List<String> flowOutputs = flowNamesOf(instance.getFlowOutputs());
            String signature = NodeSignature.of(metadata.kind(), instance);
            return new Entry(type, found.displayName(), found.categoryPath(), kind, metadata.description(),
                    metadata.keywords(), pluginId, pluginName, pluginVersion, pluginRepository,
                    inputs, outputs, flowInputs, flowOutputs, instance.isExecutionEntryPoint(),
                    instance.saveState(), signature, null);
        } catch (Throwable t) {
            log.warn("Node type \"{}\" could not be inspected for the catalog: {}", type, t.toString());
            return errorEntry(type, found, metadata, kind, pluginId, pluginName, pluginVersion,
                    pluginRepository, t.toString());
        }
    }

    private static Entry errorEntry(String type, NodeRegistry.Entry found, NodeMetadata metadata, String kind,
                                     String pluginId, String pluginName, String pluginVersion,
                                     String pluginRepository, String error) {
        return new Entry(type, found.displayName(), found.categoryPath(), kind, metadata.description(),
                metadata.keywords(), pluginId, pluginName, pluginVersion, pluginRepository,
                List.of(), List.of(), List.of(), List.of(), false, Map.of(), null, error);
    }

    @SuppressWarnings("rawtypes")
    private static List<Port> portsOf(List<NodeVariable> variables) {
        List<Port> ports = new ArrayList<>();
        for (NodeVariable variable : variables) {
            ports.add(new Port(variable.name, variable.type.getName(), variable.isRequired(),
                    variable.isSecret(), variable.manuallyEditable));
        }
        return ports;
    }

    private static List<String> flowNamesOf(List<FlowPort> ports) {
        List<String> names = new ArrayList<>();
        for (FlowPort port : ports) {
            names.add(port.name);
        }
        return names;
    }
}
