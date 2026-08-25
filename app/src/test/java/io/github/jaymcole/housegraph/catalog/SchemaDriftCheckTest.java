package io.github.jaymcole.housegraph.catalog;

import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDriftCheckTest {

    private static final NodeRegistry REGISTRY =
            new NodeRegistry(List.of(NodeRegistry.ScanRoot.core(SchemaDriftCheckTest.class.getClassLoader())));

    private static JSONObject saveWith(JSONObject... nodes) {
        return new JSONObject().put("version", 2).put("nodes", new JSONArray(List.of(nodes)));
    }

    @Test
    void noDriftWhenTheSavedSignatureStillMatchesWhatIsInstalled() {
        String currentSignature = NodeSignature.of(NodeKind.DATA, new AddNode());
        JSONObject root = saveWith(new JSONObject().put("type", "AddNode").put("nodeSignature", currentSignature));

        assertTrue(SchemaDriftCheck.inspect(root, REGISTRY).isEmpty());
    }

    @Test
    void reportsDriftWhenTheRecordedSignatureNoLongerMatches() {
        JSONObject root = saveWith(new JSONObject().put("type", "AddNode").put("nodeSignature", "0000000000000000"));

        List<SchemaDriftCheck.Drift> drift = SchemaDriftCheck.inspect(root, REGISTRY);

        assertEquals(1, drift.size());
        assertEquals("AddNode", drift.get(0).type());
        assertEquals("0000000000000000", drift.get(0).savedSignature());
    }

    @Test
    void skipsANodeThatRecordedNoSignatureAtAll() {
        // A save written before nodeSignature existed — nothing to compare, and GraphDependencyCheck
        // is what reports an actually-missing library, not this check.
        JSONObject root = saveWith(new JSONObject().put("type", "AddNode"));

        assertTrue(SchemaDriftCheck.inspect(root, REGISTRY).isEmpty());
    }

    @Test
    void skipsANodeWhoseTypeDoesNotResolveAtAll() {
        JSONObject root = saveWith(new JSONObject().put("type", "NoSuchNodeType").put("nodeSignature", "abcdefabcdefabcd"));

        assertTrue(SchemaDriftCheck.inspect(root, REGISTRY).isEmpty());
    }

    @Test
    void returnsNothingForASaveWithNoNodesArray() {
        assertTrue(SchemaDriftCheck.inspect(new JSONObject().put("version", 2), REGISTRY).isEmpty());
    }
}
