package io.github.jaymcole.housegraph.graph.nodes.module;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.modules.ModuleDirectory;
import io.github.jaymcole.housegraph.modules.ModuleEntry;
import io.github.jaymcole.housegraph.modules.ModuleInterface;
import io.github.jaymcole.housegraph.modules.ModulePort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A module node's shape: where its ports come from, that they survive without the module file, and
 * that it refuses to pretend it can run.
 */
class ModuleNodeTest {

    private static final ModulePort TEMPERATURE =
            new ModulePort("Temperature", ModulePort.Kind.DATA, ModulePort.Direction.IN, Float.class.getName());
    private static final ModulePort VERDICT =
            new ModulePort("Verdict", ModulePort.Kind.DATA, ModulePort.Direction.OUT, Boolean.class.getName());
    private static final ModulePort START =
            new ModulePort("Start", ModulePort.Kind.FLOW, ModulePort.Direction.IN, "");
    private static final ModulePort DONE =
            new ModulePort("Done", ModulePort.Kind.FLOW, ModulePort.Direction.OUT, "");

    private static final ModuleInterface FULL =
            new ModuleInterface(List.of(TEMPERATURE, VERDICT, START, DONE), List.of());

    // --- Ports ----------------------------------------------------------------------------------

    @Test
    void eachPortLandsOnTheSideTheInterfaceSays() {
        ModuleNode node = new ModuleNode();
        node.adopt(FULL);

        assertEquals(List.of("Temperature"), inputNames(node));
        assertEquals(List.of("Verdict"), outputNames(node));
        assertEquals(List.of("Start"), node.getFlowInputs().stream().map(port -> port.name).toList());
        assertEquals(List.of("Done"), node.getFlowOutputs().stream().map(port -> port.name).toList());
        assertEquals(Float.class, node.getInputs().get(0).type);
        assertEquals(Boolean.class, node.getOutputs().get(0).type);
    }

    @Test
    void aDataPortWhoseDeclaredTypeIsNotInstalledDegradesToObject() {
        ModuleNode node = new ModuleNode();
        node.adopt(new ModuleInterface(List.of(new ModulePort(
                "Reading", ModulePort.Kind.DATA, ModulePort.Direction.IN, "com.example.NotInstalled")), List.of()));

        assertEquals(Object.class, node.getInputs().get(0).type);
        assertEquals("com.example.NotInstalled", node.getModulePorts().get(0).typeName(),
                "the declaration is kept even where it cannot be read");
    }

    // --- Persistence ----------------------------------------------------------------------------

    @Test
    void theWholeShapeRoundTripsThroughSaveStateAndLoadState() {
        ModuleNode saved = new ModuleNode();
        saved.setModuleId("m-1");
        saved.adopt(FULL);
        Map<String, String> state = saved.saveState();

        ModuleNode loaded = new ModuleNode();
        loaded.loadState(state);

        assertEquals("m-1", loaded.getModuleId());
        assertEquals(FULL.ports(), loaded.getModulePorts());
    }

    @Test
    void loadStateAloneRebuildsThePortsWithNoModuleFileAnywhere() {
        ModuleNode saved = new ModuleNode();
        saved.setModuleId("m-1");
        saved.adopt(FULL);

        ModuleNode loaded = new ModuleNode();
        loaded.loadState(saved.saveState());

        // Read the ports straight after loadState: nothing has resolved anything, so the shape can
        // only have come from the saved state. This is what lets a consumer's edges bind on load.
        assertEquals(List.of("Temperature"), inputNames(loaded));
        assertEquals(List.of("Verdict"), outputNames(loaded));
        assertEquals(List.of("Start"), loaded.getFlowInputs().stream().map(port -> port.name).toList());
        assertEquals(List.of("Done"), loaded.getFlowOutputs().stream().map(port -> port.name).toList());
        assertEquals(ModuleNode.Resolution.UNCHECKED, loaded.getResolution());
    }

    @Test
    void aNodeReferencingNothingWritesNoState() {
        assertEquals(Map.of(), new ModuleNode().saveState());
    }

    // --- Resolution -----------------------------------------------------------------------------

    @Test
    void bindingToADirectoryAdoptsTheInterfaceAndTheHints() {
        ModuleNode node = new ModuleNode();
        node.setModuleId("m-1");

        assertTrue(node.bindTo(directoryOf("m-1", "Doorbell", "/somewhere/doorbell.json", FULL)));

        assertEquals(ModuleNode.Resolution.RESOLVED, node.getResolution());
        assertEquals("Doorbell", node.getModuleName());
        assertEquals("/somewhere/doorbell.json", node.getModulePath());
        assertEquals(List.of("Temperature"), inputNames(node));
        assertFalse(node.isMisconfigured());
    }

    @Test
    void anUnresolvableModuleKeepsEveryPortAndEveryEdgeAndSaysSo() {
        NodeGraph graph = new NodeGraph();
        ModuleNode node = new ModuleNode();
        node.setModuleId("m-1");
        node.adopt(FULL);
        SourceNode source = new SourceNode();
        graph.addNode(source);
        graph.addNode(node);
        Edge edge = new Edge(source, source.out, node, node.getInputs().get(0));
        graph.registerEdge(edge);

        assertFalse(node.bindTo(ModuleDirectory.EMPTY));

        assertEquals(ModuleNode.Resolution.UNRESOLVED, node.getResolution());
        assertTrue(node.isMisconfigured());
        assertEquals(List.of("Temperature"), inputNames(node));
        assertEquals(List.of("Verdict"), outputNames(node));
        assertTrue(graph.getIncomingDataEdges(node).contains(edge), "the edge must survive a failed lookup");

        ModuleNode reloaded = new ModuleNode();
        reloaded.loadState(node.saveState());
        assertEquals(FULL.ports(), reloaded.getModulePorts(), "and a re-save must lose nothing");
        assertEquals("m-1", reloaded.getModuleId());
    }

    @Test
    void aModuleWhoseBoundaryMarkersCollideIsMisconfiguredEvenThoughItResolved() {
        ModuleNode node = new ModuleNode();
        node.setModuleId("m-1");

        node.bindTo(directoryOf("m-1", "Broken", "", new ModuleInterface(
                List.of(TEMPERATURE), List.of("two Module Input markers both declare \"Temperature\""))));

        assertEquals(ModuleNode.Resolution.RESOLVED, node.getResolution());
        assertTrue(node.isMisconfigured());
        assertEquals(1, node.getInterfaceProblems().size());
    }

    @Test
    void pointingAtADifferentModuleClearsTheResolution() {
        ModuleNode node = new ModuleNode();
        node.setModuleId("m-1");
        node.bindTo(directoryOf("m-1", "Doorbell", "", FULL));

        node.setModuleId("m-2");

        assertEquals(ModuleNode.Resolution.UNCHECKED, node.getResolution());
    }

    // --- Rebuilding -----------------------------------------------------------------------------

    @Test
    void aChangedInterfaceRebuildsThePortsExactlyOnceAndDoesNotRecurse() {
        ModuleNode node = new ModuleNode();
        int[] rebuilds = {0};
        node.setPortsChangedListener(() -> {
            rebuilds[0]++;
            // Re-entry from the listener is the shape the guard exists for: rebuilding removes and
            // re-adds edges, which fires the wiring hooks, which could call straight back in here.
            node.adopt(new ModuleInterface(List.of(START), List.of()));
        });

        node.adopt(FULL);

        assertEquals(1, rebuilds[0]);
        assertEquals(FULL.ports(), node.getModulePorts());
    }

    @Test
    void adoptingTheSameInterfaceAgainRebuildsNothing() {
        ModuleNode node = new ModuleNode();
        node.adopt(FULL);
        int[] rebuilds = {0};
        node.setPortsChangedListener(() -> rebuilds[0]++);

        node.adopt(new ModuleInterface(List.of(TEMPERATURE, VERDICT, START, DONE), List.of()));

        assertEquals(0, rebuilds[0], "an unchanged shape must not churn the edges");
    }

    @Test
    void wiringAnEdgeNeverRebuildsThePorts() {
        // The trap this pins: GraphLoader wires every edge before GraphCanvas installs the
        // ports-changed listeners, so a node that rebuilt its ports from onInputEdgeAdded during a
        // load would leave the NodeView holding stale ports and the edge view would be dropped. A
        // ModuleNode's shape comes from its module, never from what is wired to it, so it must not
        // react at all.
        NodeGraph graph = new NodeGraph();
        ModuleNode node = new ModuleNode();
        node.adopt(FULL);
        int[] rebuilds = {0};
        node.setPortsChangedListener(() -> rebuilds[0]++);
        SourceNode source = new SourceNode();
        graph.addNode(source);
        graph.addNode(node);

        NodeVariable<?> before = node.getInputs().get(0);
        graph.registerEdge(new Edge(source, source.out, node, node.getInputs().get(0)));

        assertEquals(0, rebuilds[0]);
        assertSamePort(before, node.getInputs().get(0));
    }

    // --- Behaviour ------------------------------------------------------------------------------

    @Test
    void aDirectoryThatDescribesAModuleButCannotSupplyItFailsLoudly() {
        // The shape half of ModuleDirectory answered and the running half did not, which is what a
        // stub directory is. A node that quietly did nothing would leave its outputs null and read
        // to the user as a wiring mistake in their own graph.
        ModuleNode node = new ModuleNode();
        node.setModuleId("m-1");
        node.bindTo(directoryOf("m-1", "Doorbell", "", FULL));
        assertFalse(node.isMisconfigured(), "the module resolved and its interface is sound");

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> node.process(ProcessContext.uncancelled()));

        assertTrue(failure.getMessage().contains("could not be read back"), failure.getMessage());
    }

    @Test
    void anUnboundNodeRefusesToRunForTheSameReasonItReportsMisconfigured() {
        ModuleNode node = new ModuleNode();
        node.setModuleId("m-1");

        assertTrue(node.isMisconfigured(), "nothing has resolved it, so there is nothing to run it from");
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> node.process(ProcessContext.uncancelled()));
        assertTrue(failure.getMessage().contains("has not been resolved"), failure.getMessage());
    }

    @Test
    void aModuleNodeIsNeverAnExecutionEntryPoint() {
        ModuleNode node = new ModuleNode();
        // A flow OUT and no flow IN, which is the structural definition the default would match.
        node.adopt(new ModuleInterface(List.of(DONE), List.of()));

        assertFalse(node.isExecutionEntryPoint(),
                "a module runs when it is triggered or pulled; its own interior triggers fire runs"
                        + " on its own graph, which are not the invocation this node drove");
    }

    @Test
    void aNodeReferencingNoModuleIsMisconfigured() {
        assertTrue(new ModuleNode().isMisconfigured());
    }

    // --- Helpers --------------------------------------------------------------------------------

    private static void assertSamePort(NodeVariable<?> expected, NodeVariable<?> actual) {
        assertTrue(expected == actual, "the port instance itself has to survive, or a view loses track of it");
    }

    private static ModuleDirectory directoryOf(String id, String name, String path, ModuleInterface declared) {
        return lookedUp -> id.equals(lookedUp)
                ? Optional.of(new ModuleEntry(id, name, path, declared, List.of()))
                : Optional.empty();
    }

    private static List<String> inputNames(BaseNode node) {
        return node.getInputs().stream().map(variable -> variable.name).toList();
    }

    private static List<String> outputNames(BaseNode node) {
        return node.getOutputs().stream().map(variable -> variable.name).toList();
    }

    /** A minimal node with one Float output, for wiring something real into a module node. */
    private static final class SourceNode extends BaseNode {
        final NodeVariable<Float> out = new NodeVariable<>("Out", Float.class);

        @Override
        public void process(ProcessContext ctx) {
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
            addOutput(out);
        }
    }
}
