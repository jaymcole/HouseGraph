package io.github.jaymcole.housegraph.catalog;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeMetadata;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares the per-node {@code nodeSignature} a save file recorded against what the currently
 * installed node type actually looks like — the same one-pass-before-building-anything shape as
 * {@code GraphDependencyCheck}, but for "is this node still shaped the way the graph expects" rather
 * than "is the library here at all".
 *
 * <p>Unlike {@code GraphDependencyCheck}, this <b>does</b> instantiate node classes — reading a
 * node's ports needs an instance (see {@link NodeCatalog}) — so it is deliberately a separate check
 * rather than folded into {@code check}, whose whole point is answering its question without
 * loading a single class. Run this only once the libraries a graph needs are actually installed.
 *
 * <p>A node with no recorded {@code nodeSignature} (a save written before this field existed, or a
 * {@code MissingNode} placeholder) or one whose type doesn't resolve at all is silently skipped —
 * {@code GraphDependencyCheck} is what reports an unresolvable type.
 */
public final class SchemaDriftCheck {

    private static final Logger log = Log.get(SchemaDriftCheck.class);

    /**
     * One node whose currently-installed shape no longer matches what the graph was saved against.
     *
     * @param type             the saved type id
     * @param pluginId         the library recorded alongside it, or null for a core node
     * @param savedSignature   the fingerprint recorded in the save file
     * @param currentSignature the fingerprint of the type as currently installed
     */
    public record Drift(String type, String pluginId, String savedSignature, String currentSignature) {
    }

    private SchemaDriftCheck() {
    }

    /**
     * Finds every node in {@code saveRoot} whose recorded signature disagrees with the type
     * currently resolved for it.
     *
     * @param saveRoot a parsed save file (see {@code GraphFileIO.readRoot})
     * @param registry resolves each node's saved type id to a class, exactly as loading would
     * @return the drifted nodes, in file order; empty when nothing has moved
     */
    public static List<Drift> inspect(JSONObject saveRoot, NodeRegistry registry) {
        List<Drift> drifted = new ArrayList<>();
        JSONArray nodes = saveRoot.optJSONArray("nodes");
        if (nodes == null) {
            return drifted;
        }
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject nodeJson = nodes.optJSONObject(i);
            if (nodeJson == null) {
                continue;
            }
            String savedSignature = nodeJson.optString("nodeSignature", null);
            if (savedSignature == null || savedSignature.isBlank()) {
                continue;
            }
            String type = nodeJson.optString("type", null);
            String pluginId = nodeJson.optString("plugin", null);
            Class<? extends BaseNode> nodeClass = registry.resolveClass(type, pluginId);
            if (nodeClass == null) {
                continue;
            }
            String currentSignature = currentSignatureOf(nodeClass);
            if (currentSignature != null && !currentSignature.equals(savedSignature)) {
                drifted.add(new Drift(type, pluginId, savedSignature, currentSignature));
            }
        }
        return drifted;
    }

    /** Null when the type can't be instantiated to check — nothing to say, not a mismatch. */
    private static String currentSignatureOf(Class<? extends BaseNode> nodeClass) {
        try {
            BaseNode instance = NodeRegistry.instantiate(nodeClass);
            if (instance == null) {
                return null;
            }
            return NodeSignature.of(NodeMetadata.of(nodeClass).kind(), instance);
        } catch (Throwable t) {
            log.warn("Could not compute the current signature for \"{}\": {}", nodeClass.getName(), t.toString());
            return null;
        }
    }
}
