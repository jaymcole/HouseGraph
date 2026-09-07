package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleEntryNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleExitNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleInputNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleOutputNode;
import io.github.jaymcole.housegraph.loader.GraphLoader;
import io.github.jaymcole.housegraph.loader.LoadedGraph;
import io.github.jaymcole.housegraph.saveformat.CameraState;
import io.github.jaymcole.housegraph.saveformat.ClipboardFlowEdge;
import io.github.jaymcole.housegraph.saveformat.ClipboardNode;
import io.github.jaymcole.housegraph.saveformat.GraphFileIO;
import io.github.jaymcole.housegraph.saveformat.GraphSnapshot;
import io.github.jaymcole.housegraph.plugin.PluginDirectory;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static io.github.jaymcole.housegraph.modules.ModuleFixture.REGISTRY;
import static io.github.jaymcole.housegraph.modules.ModuleFixture.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pass that turns a loaded module reference into one that can run.
 *
 * <p>This is the whole of what both load paths do about modules — the canvas calls it from
 * {@code loadSnapshot} and the supervisor from {@code HeadlessGraph.open} — so it is tested here,
 * once, with no display and no canvas. What each caller adds on top is the ordering, which each of
 * them documents.
 */
class ModuleBindingTest {

    @TempDir
    Path modules;

    private NodeGraph graph;

    @AfterEach
    void tearDown() {
        if (graph != null) {
            graph.dispose();
        }
    }

    @Test
    void aSavedConsumerLoadsWithItsModuleResolvedAndRunnable() throws IOException {
        writeDoorbell("doorbell");
        LoadedGraph loaded = load(consumerReferencing("doorbell"));
        ModuleNode module = only(loaded);

        assertEquals(ModuleNode.Resolution.UNCHECKED, module.getResolution(),
                "loading does no I/O, so nothing has looked for the module yet");
        assertTrue(module.isMisconfigured(), "and an unchecked module refuses to run");

        ModuleBinding.Result result = ModuleBinding.bindAll(loaded.nodes(), library());

        assertEquals(ModuleNode.Resolution.RESOLVED, module.getResolution());
        assertFalse(module.isMisconfigured(), "which is what makes it runnable");
        assertEquals(List.of(module), result.bound());
        assertTrue(result.isComplete());
    }

    @Test
    void aModuleThatIsGoneLeavesItsNodeIntactAndUnresolved() throws IOException {
        writeDoorbell("doorbell");
        GraphSnapshot consumer = consumerReferencing("doorbell");
        // Everything the consumer was saved against, now off this machine entirely.
        LoadedGraph loaded = load(consumer);
        ModuleNode module = only(loaded);
        List<ModulePort> saved = module.getModulePorts();

        ModuleBinding.Result result = ModuleBinding.bindAll(loaded.nodes(), ModuleDirectory.EMPTY);

        assertEquals(List.of(module), result.unresolved());
        assertFalse(result.isComplete());
        assertEquals(ModuleNode.Resolution.UNRESOLVED, module.getResolution());
        assertEquals(saved, module.getModulePorts(), "the shape survives the module's absence");
        assertEquals(1, loaded.flowEdges().size(), "and so does the edge into it");
        assertTrue(module.isMisconfigured());
    }

    @Test
    void aModuleWhoseInterfaceChangedIsReportedRatherThanQuietlyReshaped() throws IOException {
        writeDoorbell("doorbell");
        GraphSnapshot consumer = consumerReferencing("doorbell");

        // The module gains a port and renames the one an edge was bound to, which is the ordinary
        // case: a module is expected to change independently of the graphs using it.
        ModuleFixture.builder(REGISTRY)
                .with(named(new ModuleEntryNode(), "Ring"),
                        named(new ModuleInputNode(), "Volume"),
                        named(new ModuleOutputNode(), "Heard"),
                        named(new ModuleExitNode(), "Done"))
                .writeTo(modules, "doorbell");

        LoadedGraph loaded = load(consumer);
        ModuleNode module = only(loaded);
        ModuleBinding.Result result = ModuleBinding.bindAll(loaded.nodes(), library());

        assertEquals(List.of(module), result.reshaped(),
                "a rebuilt shape is named, so a dropped edge is not the only sign of it");
        assertTrue(result.bound().isEmpty());
        assertTrue(result.isComplete(), "it resolved; it is only a different shape than it was");
        assertTrue(module.getModulePorts().stream().anyMatch(port -> port.name().equals("Volume")));
    }

    @Test
    void aNodeReferencingNothingIsNotSomethingToReport() {
        ModuleNode blank = new ModuleNode();

        ModuleBinding.Result result = ModuleBinding.bindAll(List.of(blank), library());

        assertEquals(0, result.total(), "an unfinished node is the user's business, not a load failure");
        assertTrue(result.isComplete());
    }

    @Test
    void everythingThatIsNotAModuleIsLeftAlone() {
        AddNode add = new AddNode();

        ModuleBinding.Result result = ModuleBinding.bindAll(List.of((BaseNode) add), library());

        assertEquals(0, result.total());
    }

    @Test
    void oneUnreadableModuleDoesNotCostTheOthers() throws IOException {
        writeDoorbell("doorbell");
        ModuleNode broken = new ModuleNode();
        broken.setModuleId("explodes");
        LoadedGraph loaded = load(consumerReferencing("doorbell"));
        ModuleNode good = only(loaded);

        ModuleDirectory throwing = id -> {
            if ("explodes".equals(id)) {
                throw new IllegalStateException("the modules directory went away mid-scan");
            }
            return library().byId(id);
        };

        ModuleBinding.Result result = ModuleBinding.bindAll(List.of(broken, good), throwing);

        assertEquals(List.of(broken), result.unresolved());
        assertEquals(List.of(good), result.bound(), "the pass carries on past one node's failure");
    }

    // --- Fixtures ---------------------------------------------------------------------------------

    /** A module with one entry, one input, one output and one exit, written as {@code <id>.json}. */
    private void writeDoorbell(String id) throws IOException {
        ModuleFixture.builder(REGISTRY)
                .with(named(new ModuleEntryNode(), "Ring"),
                        named(new ModuleInputNode(), "Loudness"),
                        named(new ModuleOutputNode(), "Heard"),
                        named(new ModuleExitNode(), "Done"))
                .writeTo(modules, id);
    }

    /**
     * A consumer graph as it comes off disk: a module node bound to {@code id} at save time, wired
     * from an Add node's flow output, then written and read back through the real save format.
     */
    private GraphSnapshot consumerReferencing(String id) {
        ModuleNode module = new ModuleNode();
        module.setModuleId(id);
        module.bindTo(library());
        JSONObject root = GraphFileIO.toJson(new GraphSnapshot(
                        List.of(new ClipboardNode(new AddNode(), 0.0, 0.0),
                                new ClipboardNode(module, 200.0, 0.0)),
                        List.of(),
                        List.of(new ClipboardFlowEdge(0, 0, 1, 0, List.of()))),
                REGISTRY, PluginDirectory.EMPTY, library(), CameraState.DEFAULT);
        return GraphFileIO.fromRoot(root, REGISTRY);
    }

    private LoadedGraph load(GraphSnapshot snapshot) {
        graph = new NodeGraph();
        return GraphLoader.load(snapshot, ClipboardNode::node, graph);
    }

    private static ModuleNode only(LoadedGraph loaded) {
        for (BaseNode node : loaded.nodes()) {
            if (node instanceof ModuleNode module) {
                return module;
            }
        }
        throw new AssertionError("the fixture graph has a module node in it");
    }

    /** A fresh library each time, so a test that rewrites a module file is not reading a cached scan. */
    private ModuleLibrary library() {
        return ModuleLibrary.over(List.of(modules), REGISTRY);
    }
}
