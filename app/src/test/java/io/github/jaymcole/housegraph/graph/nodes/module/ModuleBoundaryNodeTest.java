package io.github.jaymcole.housegraph.graph.nodes.module;

import io.github.jaymcole.housegraph.graph.FlowPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape of the four boundary markers: which port each one carries, on which side, and the
 * name that identifies it.
 * <p>
 * The port-side assertions are written as pairs — the port that must exist <em>and</em> the one
 * that must not — because the inversion is the easy thing to get backwards, and a test that only
 * checked "a Module Input has one data port" would agree with an implementation that put it on
 * the wrong side.
 */
class ModuleBoundaryNodeTest {

    // --- The inversion ---------------------------------------------------------------

    @Test
    void moduleInputCarriesADataOutputAndNoDataInput() {
        ModuleInputNode node = new ModuleInputNode();

        assertEquals(1, node.getOutputs().size(), "the value handed in surfaces on an OUTPUT");
        assertTrue(node.getInputs().isEmpty(), "nothing in this graph supplies the value, so there is no input");
        assertEquals(node.getOutputs().get(0), node.getBoundaryPort());
    }

    @Test
    void moduleOutputCarriesADataInputAndNoDataOutput() {
        ModuleOutputNode node = new ModuleOutputNode();

        assertEquals(1, node.getInputs().size(), "the interior wires the produced value into an INPUT");
        assertTrue(node.getOutputs().isEmpty(), "nothing in this graph reads the value back, so there is no output");
        assertEquals(node.getInputs().get(0), node.getBoundaryPort());
    }

    @Test
    void moduleEntryCarriesAFlowOutputAndNoFlowInput() {
        ModuleEntryNode node = new ModuleEntryNode();

        assertEquals(1, node.getFlowOutputs().size(), "control arriving at the graph emerges from an OUT port");
        assertEquals(FlowPort.Direction.OUT, node.getFlowOutputs().get(0).direction);
        assertTrue(node.getFlowInputs().isEmpty(), "arrival is what this node is; nothing cascades into it");
    }

    @Test
    void moduleExitCarriesAFlowInputAndNoFlowOutput() {
        ModuleExitNode node = new ModuleExitNode();

        assertEquals(1, node.getFlowInputs().size(), "the interior wires control into an IN port");
        assertEquals(FlowPort.Direction.IN, node.getFlowInputs().get(0).direction);
        assertTrue(node.getFlowOutputs().isEmpty(), "leaving is what this node is; there is nothing downstream");
    }

    @Test
    void theDataMarkersDeclareNoFlowAndTheFlowMarkersNoData() {
        for (ModuleDataBoundaryNode node : List.of(new ModuleInputNode(), new ModuleOutputNode())) {
            assertTrue(node.getFlowInputs().isEmpty(), node.getClass().getSimpleName() + " must not declare flow");
            assertTrue(node.getFlowOutputs().isEmpty(), node.getClass().getSimpleName() + " must not declare flow");
        }
        for (ModuleBoundaryNode node : List.of(new ModuleEntryNode(), new ModuleExitNode())) {
            assertTrue(node.getInputs().isEmpty(), node.getClass().getSimpleName() + " must not declare data");
            assertTrue(node.getOutputs().isEmpty(), node.getClass().getSimpleName() + " must not declare data");
        }
    }

    @Test
    void moduleEntryReadsAsAnExecutionEntryPoint() {
        // A structural consequence of the inversion, not a special case: a flow output with no
        // flow input can only ever be run directly, which is how a module will start its interior.
        assertTrue(new ModuleEntryNode().isExecutionEntryPoint());
        assertFalse(new ModuleExitNode().isExecutionEntryPoint());
    }

    // --- The declared name -----------------------------------------------------------

    @Test
    void theNameRoundTripsThroughSaveAndLoadForEveryMarker() {
        for (ModuleBoundaryNode node : freshMarkers()) {
            node.setDeclaredName("Motion detected");
            Map<String, String> state = node.saveState();

            ModuleBoundaryNode loaded = fresh(node.getClass());
            loaded.loadState(state);

            assertEquals("Motion detected", loaded.getDeclaredName(), node.getClass().getSimpleName());
        }
    }

    @Test
    void theNameIsStoredExactlyAsTyped() {
        ModuleInputNode node = new ModuleInputNode();
        node.setDeclaredName("  Room Name  ");

        assertEquals("  Room Name  ", node.getDeclaredName(), "a name is a binding key, not a label to tidy up");
        assertEquals("  Room Name  ", node.saveState().get("name"));
    }

    @Test
    void aBlankNameLeavesEveryMarkerMisconfigured() {
        for (ModuleBoundaryNode node : freshMarkers()) {
            String type = node.getClass().getSimpleName();
            assertTrue(node.isMisconfigured(), type + " starts with no name declared");

            node.setDeclaredName("   ");
            assertTrue(node.isMisconfigured(), type + " with a whitespace-only name has no binding key either");

            node.setDeclaredName("brightness");
            assertFalse(node.isMisconfigured(), type + " is configured once it is named");
        }
    }

    @Test
    void renamingDoesNotDisturbTheDataPort() {
        // The port name is fixed, so an edge inside the graph still binds after the module's
        // interface is renamed. Naming the port after the declaration would silently drop it.
        ModuleInputNode node = new ModuleInputNode();
        node.setDeclaredName("before");
        Object portBeforeRename = node.getBoundaryPort();

        node.setDeclaredName("after");

        assertEquals(portBeforeRename, node.getBoundaryPort(), "renaming must not rebuild the port");
        assertEquals("Value", node.getBoundaryPort().name);
    }

    @Test
    void aMarkerWithNothingDeclaredSavesNoName() {
        assertFalse(new ModuleEntryNode().saveState().containsKey("name"));
    }

    private static List<ModuleBoundaryNode> freshMarkers() {
        return List.of(new ModuleEntryNode(), new ModuleExitNode(), new ModuleInputNode(), new ModuleOutputNode());
    }

    private static ModuleBoundaryNode fresh(Class<? extends ModuleBoundaryNode> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("every node type needs a no-arg constructor", e);
        }
    }
}
