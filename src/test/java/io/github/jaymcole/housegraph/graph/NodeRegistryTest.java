package io.github.jaymcole.housegraph.graph;

import io.github.jaymcole.housegraph.graph.nodes.control.TriggerNode;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.graph.nodes.math.ConstantFloatNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRegistryTest {

    @Test
    void discoversKnownNodeClasses() {
        List<NodeRegistry.Entry> entries = NodeRegistry.discover();
        List<Class<? extends BaseNode>> classes = entries.stream().map(NodeRegistry.Entry::nodeClass).toList();

        assertTrue(classes.contains(AddNode.class));
        assertTrue(classes.contains(ConstantFloatNode.class));
        assertTrue(classes.contains(TriggerNode.class));
    }

    @Test
    void categoryPathMatchesSubpackage() {
        List<NodeRegistry.Entry> entries = NodeRegistry.discover();

        String addNodeCategory = entries.stream()
                .filter(entry -> entry.nodeClass() == AddNode.class)
                .findFirst()
                .orElseThrow()
                .categoryPath();

        assertEquals("math", addNodeCategory);
    }

    @Test
    void displayNameUsesTheDisplayNameAnnotation() {
        List<NodeRegistry.Entry> entries = NodeRegistry.discover();

        String name = entries.stream()
                .filter(entry -> entry.nodeClass() == ConstantFloatNode.class)
                .findFirst()
                .orElseThrow()
                .displayName();

        assertEquals("Float Constant", name);
    }

    @Test
    void instantiateBuildsAWorkingNode() {
        BaseNode node = NodeRegistry.instantiate(AddNode.class);
        assertTrue(node instanceof AddNode);
    }
}
