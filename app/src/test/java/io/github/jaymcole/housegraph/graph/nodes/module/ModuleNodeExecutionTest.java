package io.github.jaymcole.housegraph.graph.nodes.module;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.ExecutionPolicy;
import io.github.jaymcole.housegraph.graph.FlowEdge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.graph.nodes.control.IfBoolNode;
import io.github.jaymcole.housegraph.graph.nodes.control.TriggerNode;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.graph.nodes.module.fixture.BarrierFixtureNode;
import io.github.jaymcole.housegraph.graph.nodes.module.fixture.GateFixtureNode;
import io.github.jaymcole.housegraph.graph.nodes.module.fixture.LifecycleFixtureNode;
import io.github.jaymcole.housegraph.graph.nodes.module.fixture.ResourceFixtureNode;
import io.github.jaymcole.housegraph.modules.ModuleDirectory;
import io.github.jaymcole.housegraph.modules.ModuleFixture;
import io.github.jaymcole.housegraph.modules.ModuleInstance;
import io.github.jaymcole.housegraph.modules.ModuleLibrary;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A module actually running: real module files on disk, resolved through a real
 * {@link ModuleLibrary}, driven from a real {@link NodeGraph} with <b>no JavaFX toolkit started</b>.
 *
 * <p>Nothing here sleeps. Where a test needs two things to overlap it uses a latch or a barrier, so
 * a loaded machine takes longer to satisfy the wait but never reports a different result.
 */
class ModuleNodeExecutionTest {

    /** Long enough that a loaded CI machine still gets there; each wait returns as soon as it holds. */
    private static final long AWAIT_MILLIS = 10_000;

    @TempDir
    Path modules;

    private NodeGraph graph;

    @AfterEach
    void tearDown() {
        if (graph != null) {
            graph.dispose();
        }
    }

    // --- Flow ------------------------------------------------------------------------------------

    @Test
    void triggeringTheFlowInPortRunsTheInteriorAndFiresThePortForTheExitReached() throws IOException {
        // Start ▸ Add ▸ Done, with the two addends coming in as module inputs and the sum going out.
        ModuleEntryNode start = ModuleFixture.named(new ModuleEntryNode(), "Start");
        ModuleInputNode a = typed(ModuleFixture.named(new ModuleInputNode(), "A"), Float.class);
        ModuleInputNode b = typed(ModuleFixture.named(new ModuleInputNode(), "B"), Float.class);
        ModuleOutputNode sum = typed(ModuleFixture.named(new ModuleOutputNode(), "Sum"), Float.class);
        ModuleExitNode done = ModuleFixture.named(new ModuleExitNode(), "Done");
        AddNode add = new AddNode();
        ModuleFixture.builder(ModuleFixture.REGISTRY)
                .with(start, a, b, sum, done, add)
                .flow(start, 0, add, 0)
                .flow(add, 0, done, 0)
                .data(a, 0, add, 0)
                .data(b, 0, add, 1)
                .data(add, 0, sum, 0)
                .writeTo(modules, "adder");

        graph = new NodeGraph();
        ModuleNode module = bound("adder");
        graph.addNode(module);
        feed(module, "A", 2f);
        feed(module, "B", 40f);
        TriggerNode trigger = new TriggerNode();
        graph.addNode(trigger);
        graph.registerFlowEdge(new FlowEdge(trigger, trigger.getFlowOutputs().get(0), module, flowIn(module, "Start")));
        RecordingNode after = new RecordingNode();
        graph.addNode(after);
        graph.registerFlowEdge(new FlowEdge(module, flowOut(module, "Done"), after, after.getFlowInputs().get(0)));

        trigger.execute();
        graph.awaitIdle();

        assertEquals(42f, output(module, "Sum").getValue(),
                "the interior ran and its Module Output came back out");
        assertEquals(1, after.firings.size(), "the exit that was reached fired the parent port matching it");
    }

    @Test
    void aBranchInsideAModuleActivatesOnlyTheParentPortForTheExitItReached() throws IOException {
        writeBranchingModule();

        graph = new NodeGraph();
        ModuleNode module = bound("branch");
        graph.addNode(module);
        feed(module, "Flag", Boolean.FALSE);
        TriggerNode trigger = new TriggerNode();
        graph.addNode(trigger);
        graph.registerFlowEdge(new FlowEdge(trigger, trigger.getFlowOutputs().get(0), module, flowIn(module, "Start")));
        RecordingNode onYes = new RecordingNode();
        RecordingNode onNo = new RecordingNode();
        graph.addNode(onYes);
        graph.addNode(onNo);
        graph.registerFlowEdge(new FlowEdge(module, flowOut(module, "Yes"), onYes, onYes.getFlowInputs().get(0)));
        graph.registerFlowEdge(new FlowEdge(module, flowOut(module, "No"), onNo, onNo.getFlowInputs().get(0)));

        trigger.execute();
        graph.awaitIdle();

        assertEquals(0, onYes.firings.size(), "the branch the module did not take fires nothing");
        assertEquals(1, onNo.firings.size(), "and the one it took fires exactly once");
    }

    // --- The inversion ---------------------------------------------------------------------------

    @Test
    void aModuleInputIsADataInPortOutsideAndADataOutputInside() throws IOException {
        writePassThroughModule();

        graph = new NodeGraph();
        ModuleNode module = bound("passthrough");
        graph.addNode(module);

        assertEquals(List.of("In"), names(module.getInputs()),
                "the marker called an input lands on the consumer's IN side");
        assertEquals(List.of("Out"), names(module.getOutputs()));

        // And the same inversion on the interior face, which is where getting it backwards would make
        // the module silently read nothing: the marker's own port points the other way.
        ModuleInstance instance = ModuleInstance.open("passthrough", library(), graph, 1);
        try {
            ModuleInputNode marker = only(instance, ModuleInputNode.class);
            assertSame(marker.getOutputs().get(0), marker.getBoundaryPort(),
                    "a Module Input's own port is a data OUTPUT — the interior wires away from it");
            assertTrue(marker.getInputs().isEmpty());
            ModuleOutputNode result = only(instance, ModuleOutputNode.class);
            assertSame(result.getInputs().get(0), result.getBoundaryPort(),
                    "and a Module Output's own port is a data INPUT — the interior wires into it");
            assertTrue(result.getOutputs().isEmpty());
        } finally {
            instance.dispose();
        }
    }

    @Test
    void theValueOnTheParentsDataInPortIsWhatTheInteriorReads() throws IOException {
        writePassThroughModule();

        graph = new NodeGraph();
        ModuleNode module = bound("passthrough");
        graph.addNode(module);
        feed(module, "In", "carried across");

        module.beginProcessing();

        assertEquals("carried across", output(module, "Out").getValue());
    }

    // --- Pulling ---------------------------------------------------------------------------------

    @Test
    void aPureDataModuleResolvesWhenItIsPulled() throws IOException {
        writePassThroughModule();

        graph = new NodeGraph();
        ModuleNode module = bound("passthrough");
        ConstantStringSource source = new ConstantStringSource("pulled");
        graph.addNode(source);
        graph.addNode(module);
        graph.registerEdge(new Edge(source, source.getOutputs().get(0), module, input(module, "In")));

        module.beginProcessing();

        assertEquals("pulled", output(module, "Out").getValue(),
                "a module with no Module Entry answers a pull the way any data node does");
        assertTrue(module.getFlowInputs().isEmpty(), "and declares no flow-in port to be triggered through");
    }

    @Test
    void aModuleWithEntriesThatIsPulledAnswersWithDataAndFiresNothing() throws IOException {
        writeBranchingModule();

        graph = new NodeGraph();
        ModuleNode module = bound("branch");
        graph.addNode(module);
        feed(module, "Flag", Boolean.TRUE);
        RecordingNode onYes = new RecordingNode();
        graph.addNode(onYes);
        graph.registerFlowEdge(new FlowEdge(module, flowOut(module, "Yes"), onYes, onYes.getFlowInputs().get(0)));

        // Triggered directly rather than along a flow edge, so nothing arrived at a flow-in port.
        module.execute();
        graph.awaitIdle();

        assertEquals(0, onYes.firings.size(),
                "a pull resolves the module's outputs and fires no Entry, so no Exit can select a port");
    }

    // --- Concurrency ------------------------------------------------------------------------------

    @Test
    void twoParallelInvocationsOfOneModuleDoNotSeeEachOthersValues() throws IOException {
        ModuleEntryNode start = ModuleFixture.named(new ModuleEntryNode(), "Start");
        ModuleInputNode in = typed(ModuleFixture.named(new ModuleInputNode(), "In"), Float.class);
        ModuleOutputNode out = typed(ModuleFixture.named(new ModuleOutputNode(), "Out"), Float.class);
        ModuleExitNode done = ModuleFixture.named(new ModuleExitNode(), "Done");
        BarrierFixtureNode barrier = new BarrierFixtureNode();
        ModuleFixture.builder(ModuleFixture.REGISTRY)
                .with(start, in, out, done, barrier)
                .flow(start, 0, barrier, 0)
                .flow(barrier, 0, done, 0)
                .data(in, 0, barrier, 0)
                .data(barrier, 0, out, 0)
                .writeTo(modules, "barrier");

        graph = new NodeGraph();
        ModuleNode module = bound("barrier");
        // Without this the two runs would queue at the module and the barrier inside it would never
        // find a second invocation to meet.
        module.setExecutionPolicy(ExecutionPolicy.PARALLEL);
        FloatEventSource source = new FloatEventSource();
        source.setExecutionPolicy(ExecutionPolicy.PARALLEL);
        CapturingNode captured = new CapturingNode();
        graph.addNode(source);
        graph.addNode(module);
        graph.addNode(captured);
        graph.registerEdge(new Edge(source, source.getOutputs().get(0), module, input(module, "In")));
        graph.registerFlowEdge(new FlowEdge(source, source.getFlowOutputs().get(0), module, flowIn(module, "Start")));
        graph.registerEdge(new Edge(module, output(module, "Out"), captured, captured.getInputs().get(0)));
        graph.registerFlowEdge(new FlowEdge(module, flowOut(module, "Done"), captured, captured.getFlowInputs().get(0)));

        source.fire(1f);
        source.fire(2f);
        graph.awaitIdle();

        // The barrier only trips when both invocations are inside the module at once, so getting here
        // at all means they really did overlap — and each still carried its own argument out.
        assertEquals(List.of(1f, 2f), captured.values.stream().sorted().toList(),
                "each concurrent invocation harvested its own run's value, not the other's");
    }

    @Test
    void cancellingTheDrivingRunStopsTheModulesRun() throws IOException, InterruptedException {
        ModuleEntryNode start = ModuleFixture.named(new ModuleEntryNode(), "Start");
        GateFixtureNode gate = new GateFixtureNode();
        ModuleFixture.builder(ModuleFixture.REGISTRY)
                .with(start, gate)
                .flow(start, 0, gate, 0)
                .writeTo(modules, "gated");

        graph = new NodeGraph();
        ModuleNode module = bound("gated");
        graph.addNode(module);
        TriggerNode trigger = new TriggerNode();
        // RESTART: the second trigger supersedes the first run, which is the cancellation that has to
        // cross into the module.
        trigger.setExecutionPolicy(ExecutionPolicy.RESTART);
        graph.addNode(trigger);
        graph.registerFlowEdge(new FlowEdge(trigger, trigger.getFlowOutputs().get(0), module, flowIn(module, "Start")));

        trigger.execute();
        assertTrue(GateFixtureNode.ENTERED.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS),
                "the module's run reached the node that waits");
        trigger.execute();

        assertTrue(GateFixtureNode.CANCELLED.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS),
                "cancelling the driving run is seen by a node inside the module it drove");
        // Let the superseding run's own firing finish so the graph can be disposed.
        GateFixtureNode.RELEASE.countDown();
        graph.awaitIdle();
    }

    @Test
    void twoInstancesOfAModuleThatPublishesAResourceNameCollide() throws IOException {
        ModuleInputNode in = typed(ModuleFixture.named(new ModuleInputNode(), "In"), String.class);
        ModuleOutputNode out = typed(ModuleFixture.named(new ModuleOutputNode(), "Out"), String.class);
        ModuleFixture.builder(ModuleFixture.REGISTRY)
                .with(in, out, new ResourceFixtureNode())
                .data(in, 0, out, 0)
                .writeTo(modules, "resourceful");

        graph = new NodeGraph();
        ModuleNode first = bound("resourceful");
        ModuleNode second = bound("resourceful");
        graph.addNode(first);
        graph.addNode(second);
        int registeredBefore = ResourceFixtureNode.REGISTERED.size();
        first.beginProcessing();
        second.beginProcessing();

        // The documented consequence, asserted rather than hoped for: registry names are app-wide, so
        // the second instance displaces the first. ModuleInstance logs a warning naming the module;
        // nothing renames the resource, which is why a module that publishes a name is usable once.
        assertEquals(registeredBefore + 2, ResourceFixtureNode.REGISTERED.size(),
                "each reference stands up its own copy of the module, resource node and all");
        ResourceFixtureNode holder = ResourceRegistry.shared()
                .find(ResourceFixtureNode.NAME, ResourceFixtureNode.class).orElseThrow();
        assertSame(ResourceFixtureNode.REGISTERED.get(registeredBefore + 1), holder,
                "the later registration wins, and the earlier resource is unreachable by name");
    }

    // --- Teardown ---------------------------------------------------------------------------------

    @Test
    void removingTheModuleNodeDisposesTheModulesGraph() throws IOException, InterruptedException {
        writeLifecycleModule();
        int activationsBefore = LifecycleFixtureNode.ACTIVATIONS.get();

        graph = new NodeGraph();
        ModuleNode module = bound("lifecycle");
        graph.addNode(module);
        module.beginProcessing();
        assertEquals(activationsBefore + 1, LifecycleFixtureNode.ACTIVATIONS.get(),
                "the module's graph was stood up on first use");

        graph.removeNode(module);

        assertTrue(LifecycleFixtureNode.RELEASED.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS),
                "the module's own nodes are released, so nothing it holds is left running");
        assertEquals(1, LifecycleFixtureNode.REMOVALS.get());
    }

    // --- Refusals ---------------------------------------------------------------------------------

    @Test
    void anUnresolvedModuleFailsWithoutStandingUpAGraph() throws IOException {
        writeLifecycleModule();
        int activationsBefore = LifecycleFixtureNode.ACTIVATIONS.get();

        graph = new NodeGraph();
        ModuleNode module = new ModuleNode();
        module.setModuleId("lifecycle");
        // A directory that knows nothing: the file is right there, and nothing has found it.
        module.bindTo(ModuleDirectory.EMPTY);
        graph.addNode(module);

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> module.process(ProcessContext.uncancelled()));

        assertTrue(failure.getMessage().contains("not found"), failure.getMessage());
        assertEquals(activationsBefore, LifecycleFixtureNode.ACTIVATIONS.get(),
                "nothing was loaded, so no node in the module was ever activated");
    }

    @Test
    void aModuleWhoseInterfaceIsUnsoundRefusesToRun() throws IOException {
        // Two Module Inputs declaring one name: an interface a consumer cannot bind an edge to.
        ModuleFixture.builder(ModuleFixture.REGISTRY)
                .with(typed(ModuleFixture.named(new ModuleInputNode(), "In"), String.class),
                        typed(ModuleFixture.named(new ModuleInputNode(), "In"), String.class))
                .writeTo(modules, "collide");

        graph = new NodeGraph();
        ModuleNode module = new ModuleNode();
        module.setModuleId("collide");
        assertTrue(module.bindTo(library()), "the module is found — it is its interface that is unusable");
        graph.addNode(module);

        assertTrue(module.isMisconfigured(), "an interface nothing can bind to is a misconfigured node");
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> module.process(ProcessContext.uncancelled()));
        assertTrue(failure.getMessage().contains("unique"), failure.getMessage());
    }

    // --- Nesting ----------------------------------------------------------------------------------

    @Test
    void aModuleInsideAModuleRuns() throws IOException {
        writePassThroughModule();
        writeWrapperModule();

        graph = new NodeGraph();
        ModuleNode module = bound("wrapper");
        graph.addNode(module);
        feed(module, "Outer In", "two deep");

        module.beginProcessing();

        assertEquals("two deep", output(module, "Outer Out").getValue(),
                "the value went in through two modules and came back out through both");
    }

    @Test
    void nestingPastTheCapFailsWithAClearError() throws IOException {
        writePassThroughModule();
        writeWrapperModule();
        graph = new NodeGraph();

        // Built at the cap, so the ModuleNode inside it would sit one deeper.
        ModuleInstance atTheCap = ModuleInstance.open("wrapper", library(), graph, ModuleInstance.MAX_DEPTH);
        try {
            ModuleNode nested = only(atTheCap, ModuleNode.class);
            IllegalStateException failure =
                    assertThrows(IllegalStateException.class, () -> nested.process(ProcessContext.uncancelled()));
            assertTrue(failure.getMessage().contains("nested more than " + ModuleInstance.MAX_DEPTH),
                    failure.getMessage());
        } finally {
            atTheCap.dispose();
        }

        assertThrows(IllegalStateException.class,
                () -> ModuleInstance.open("passthrough", library(), graph, ModuleInstance.MAX_DEPTH + 1),
                "and the cap is enforced wherever an instance is opened, not only from a node");
    }

    // --- Module fixtures ---------------------------------------------------------------------------

    /** Input ▸ Output, wired straight through: the smallest module with no control flow at all. */
    private void writePassThroughModule() throws IOException {
        ModuleInputNode in = typed(ModuleFixture.named(new ModuleInputNode(), "In"), String.class);
        ModuleOutputNode out = typed(ModuleFixture.named(new ModuleOutputNode(), "Out"), String.class);
        ModuleFixture.builder(ModuleFixture.REGISTRY)
                .with(in, out)
                .data(in, 0, out, 0)
                .writeTo(modules, "passthrough");
    }

    /** A module whose whole body is a reference to {@code passthrough}. */
    private void writeWrapperModule() throws IOException {
        ModuleInputNode in = typed(ModuleFixture.named(new ModuleInputNode(), "Outer In"), String.class);
        ModuleOutputNode out = typed(ModuleFixture.named(new ModuleOutputNode(), "Outer Out"), String.class);
        ModuleNode inner = new ModuleNode();
        inner.setModuleId("passthrough");
        inner.bindTo(library());
        ModuleFixture.builder(ModuleFixture.REGISTRY)
                .with(in, out, inner)
                .data(in, 0, inner, 0)
                .data(inner, 0, out, 0)
                .writeTo(modules, "wrapper");
    }

    /** Start ▸ If ▸ one of two exits, so exactly one parent flow-out port may fire. */
    private void writeBranchingModule() throws IOException {
        ModuleEntryNode start = ModuleFixture.named(new ModuleEntryNode(), "Start");
        ModuleInputNode flag = typed(ModuleFixture.named(new ModuleInputNode(), "Flag"), Boolean.class);
        ModuleExitNode yes = ModuleFixture.named(new ModuleExitNode(), "Yes");
        ModuleExitNode no = ModuleFixture.named(new ModuleExitNode(), "No");
        IfBoolNode branch = new IfBoolNode();
        ModuleFixture.builder(ModuleFixture.REGISTRY)
                .with(start, flag, yes, no, branch)
                .flow(start, 0, branch, 0)
                .data(flag, 0, branch, 0)
                .flow(branch, 0, yes, 0)
                .flow(branch, 1, no, 0)
                .writeTo(modules, "branch");
    }

    /** A module holding one node that reports every lifecycle hook the engine calls on it. */
    private void writeLifecycleModule() throws IOException {
        ModuleInputNode in = typed(ModuleFixture.named(new ModuleInputNode(), "In"), String.class);
        ModuleOutputNode out = typed(ModuleFixture.named(new ModuleOutputNode(), "Out"), String.class);
        ModuleFixture.builder(ModuleFixture.REGISTRY)
                .with(in, out, new LifecycleFixtureNode())
                .data(in, 0, out, 0)
                .writeTo(modules, "lifecycle");
    }

    // --- Helpers ------------------------------------------------------------------------------------

    private ModuleLibrary library() {
        return ModuleLibrary.over(List.of(modules), ModuleFixture.REGISTRY);
    }

    /** A module node already pointed at {@code id} and resolved, which is what makes it runnable. */
    private ModuleNode bound(String id) {
        ModuleNode node = new ModuleNode();
        node.setModuleId(id);
        assertTrue(node.bindTo(library()), "the fixture module should resolve");
        assertFalse(node.isMisconfigured(), "and present a sound interface");
        return node;
    }

    private static <T extends ModuleDataBoundaryNode> T typed(T marker, Class<?> type) {
        marker.setDeclaredType(type);
        return marker;
    }

    private static void feed(ModuleNode module, String port, Object value) {
        set(input(module, port), value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void set(NodeVariable variable, Object value) {
        variable.setValue(value);
    }

    private static NodeVariable<?> input(ModuleNode module, String name) {
        return module.getInputs().stream().filter(variable -> variable.name.equals(name)).findFirst().orElseThrow();
    }

    private static NodeVariable<?> output(ModuleNode module, String name) {
        return module.getOutputs().stream().filter(variable -> variable.name.equals(name)).findFirst().orElseThrow();
    }

    private static FlowPort flowIn(ModuleNode module, String name) {
        return module.getFlowInputs().stream().filter(port -> port.name.equals(name)).findFirst().orElseThrow();
    }

    private static FlowPort flowOut(ModuleNode module, String name) {
        return module.getFlowOutputs().stream().filter(port -> port.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<String> names(List<NodeVariable> variables) {
        List<String> names = new ArrayList<>();
        for (NodeVariable variable : variables) {
            names.add(variable.name);
        }
        return names;
    }

    private static <T extends BaseNode> T only(NodeGraph graph, Class<T> type) {
        return graph.getNodes().stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }

    // --- Nodes that only exist here ------------------------------------------------------------------

    /** Records that it fired, so a test can tell which of a module's exits selected a parent port. */
    private static final class RecordingNode extends BaseNode {

        private final List<String> firings = new CopyOnWriteArrayList<>();

        @Override
        public void process(ProcessContext ctx) {
            firings.add("fired");
        }

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
        }

        @Override
        public void configureFlowInputs() {
            addFlowInput(new FlowPort("", FlowPort.Direction.IN));
        }
    }

    /** Captures the value it was handed on each firing, which is per-run and so survives overlap. */
    private static final class CapturingNode extends BaseNode {

        private final NodeVariable<Float> in = new NodeVariable<>("In", Float.class);
        private final List<Float> values = new CopyOnWriteArrayList<>();

        @Override
        public void process(ProcessContext ctx) {
            values.add(in.getValue());
        }

        @Override
        public void configureInputs() {
            addInput(in);
        }

        @Override
        public void configureOutputs() {
        }

        @Override
        public void configureFlowInputs() {
            addFlowInput(new FlowPort("", FlowPort.Direction.IN));
        }
    }

    /** An event source: each {@link #fire} starts its own run carrying its own value. */
    private static final class FloatEventSource extends BaseNode {

        private final NodeVariable<Float> out = new NodeVariable<>("Out", Float.class);

        void fire(float value) {
            execute(() -> out.setValue(value));
        }

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

        @Override
        public void configureFlowOutputs() {
            addFlowOutput(new FlowPort("", FlowPort.Direction.OUT));
        }
    }

    /** A string constant, since the core one is a float. */
    private static final class ConstantStringSource extends BaseNode {

        private final NodeVariable<String> out = new NodeVariable<>("Out", String.class);

        ConstantStringSource(String value) {
            out.setValue(value);
        }

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
