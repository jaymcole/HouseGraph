package io.github.jaymcole.housegraph.graph;

import io.github.jaymcole.housegraph.graph.nodes.control.TriggerNode;
import io.github.jaymcole.housegraph.graph.nodes.debug.DisabledNode;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.graph.nodes.constants.ConstantFloatNode;
import io.github.jaymcole.housegraph.graph.nodes.loader.SecretLoaderNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRegistryTest {

    // --- Stable save-file type identity ---------------------------------------------------------

    @Test
    void persistentTypeIdDefaultsToSimpleClassName() {
        assertEquals("AddNode", NodeRegistry.persistentTypeId(AddNode.class),
                "an unannotated node persists under its simple class name (decoupled from its package)");
    }

    @Test
    void persistentTypeIdUsesAnExplicitNodeTypeValue() {
        assertEquals("disabled-fixture", NodeRegistry.persistentTypeId(DisabledNode.class),
                "a @Node.Type value overrides the simple-class-name default");
    }

    @Test
    void resolveClassBySimpleName() {
        assertSame(AddNode.class, NodeRegistry.resolveClass("AddNode"),
                "the simple class name is the default persisted id and resolves back");
    }

    @Test
    void resolveClassByFullyQualifiedNameStillWorksForOldSaves() {
        assertSame(AddNode.class, NodeRegistry.resolveClass(AddNode.class.getName()),
                "a pre-type-id save stored the class name; it must still resolve via the fallback");
    }

    @Test
    void resolveClassByExplicitTypeIdAndAlias() {
        // A @Node.Type node resolves by its id and by any alias - the path that keeps old saves
        // loading after a class is renamed. DisabledNode is disabled yet still indexed, on purpose.
        assertSame(DisabledNode.class, NodeRegistry.resolveClass("disabled-fixture"), "resolves by its @Node.Type id");
        assertSame(DisabledNode.class, NodeRegistry.resolveClass("legacy.disabled.fixture.id"), "resolves by an alias");
        assertSame(DisabledNode.class, NodeRegistry.resolveClass("DisabledNode"), "still resolves by its simple name too");
    }

    @Test
    void resolveClassReturnsNullForAnUnknownType() {
        assertNull(NodeRegistry.resolveClass("com.example.NotARealNode"));
        assertNull(NodeRegistry.resolveClass("no-such-id"));
        assertNull(NodeRegistry.resolveClass(null));
    }

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
    void duplicateCopiesManuallyAuthoredValues() {
        ConstantFloatNode original = new ConstantFloatNode();
        original.getOutputs().get(0).setValue(3f); // manually-editable constant

        BaseNode copy = NodeRegistry.duplicate(original);

        assertTrue(copy instanceof ConstantFloatNode);
        assertEquals(3f, copy.getOutputs().get(0).getValue());
    }

    @SuppressWarnings("unchecked")
    @Test
    void duplicateDoesNotCopyComputedValues() {
        // AddNode's inputs and sum output are not manually editable — they only ever hold
        // values pulled off edges or computed by process(). Those must not be carried across
        // as manual entries (mirrors the save-file persistence discipline).
        AddNode original = new AddNode();
        original.getInputs().get(0).setValue(3f);
        original.getOutputs().get(0).setValue(99f);

        BaseNode copy = NodeRegistry.duplicate(original);

        assertTrue(copy instanceof AddNode);
        assertNull(copy.getInputs().get(0).getValue());
        assertNull(copy.getOutputs().get(0).getValue());
    }

    @SuppressWarnings("unchecked")
    @Test
    void duplicateDoesNotCopySecretValues() {
        // Regression: copying a node whose value was resolved off a secret used to paste the
        // secret in plaintext as a manual entry. A secret-marked variable must never transfer.
        SecretLoaderNode original = new SecretLoaderNode();
        original.getOutputs().get(0).setValue("hunter2");

        BaseNode copy = NodeRegistry.duplicate(original);

        assertTrue(copy instanceof SecretLoaderNode);
        assertNull(copy.getOutputs().get(0).getValue());
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
