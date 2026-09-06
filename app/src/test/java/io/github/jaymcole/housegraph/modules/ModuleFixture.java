package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleBoundaryNode;
import io.github.jaymcole.housegraph.saveformat.ClipboardNode;
import io.github.jaymcole.housegraph.saveformat.GraphSnapshot;
import io.github.jaymcole.housegraph.saveformat.SaveFileFixture;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Builds real module save files, so no test has to hand-write JSON that would drift from the format. */
final class ModuleFixture {

    static final NodeRegistry REGISTRY =
            new NodeRegistry(List.of(NodeRegistry.ScanRoot.core(ModuleFixture.class.getClassLoader())));

    private ModuleFixture() {
    }

    /** A boundary marker with its name already declared, ready to drop into a fixture graph. */
    static <T extends ModuleBoundaryNode> T named(T marker, String name) {
        marker.setDeclaredName(name);
        return marker;
    }

    /** The save file a graph of these nodes would produce, with no module identity yet. */
    static JSONObject graphOf(BaseNode... nodes) {
        List<ClipboardNode> entries = new ArrayList<>();
        for (BaseNode node : nodes) {
            entries.add(new ClipboardNode(node, 0.0, 0.0));
        }
        return SaveFileFixture.toJson(new GraphSnapshot(entries, List.of(), List.of()), REGISTRY);
    }

    /** The same, already carrying the module id {@code id}. */
    static JSONObject moduleOf(String id, BaseNode... nodes) {
        JSONObject root = graphOf(nodes);
        root.put(ModuleFile.MODULE_KEY, new JSONObject().put("id", id));
        return root;
    }
}
