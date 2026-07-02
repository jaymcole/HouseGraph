package io.github.jaymcole.housegraph.graph;

import io.github.jaymcole.housegraph.graph.nodes.control.TriggerNode;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.graph.nodes.constants.ConstantFloatNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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

    @SuppressWarnings("unchecked")
    @Test
    void duplicateCopiesInputAndOutputValuesByPosition() {
        AddNode original = new AddNode();
        original.getInputs().get(0).setValue(3f);
        original.getOutputs().get(0).setValue(99f);

        BaseNode copy = NodeRegistry.duplicate(original);

        assertTrue(copy instanceof AddNode);
        assertEquals(3f, copy.getInputs().get(0).getValue());
        assertEquals(99f, copy.getOutputs().get(0).getValue());
    }

    @SuppressWarnings("unchecked")
    @Test
    void duplicateIsIndependentFromTheOriginal() {
        ConstantFloatNode original = new ConstantFloatNode();
        original.getOutputs().get(0).setValue(1f);

        BaseNode copy = NodeRegistry.duplicate(original);
        assertNotSame(original, copy);

        copy.getOutputs().get(0).setValue(2f);

        assertEquals(1f, original.getOutputs().get(0).getValue());
        assertEquals(2f, copy.getOutputs().get(0).getValue());
    }
}
