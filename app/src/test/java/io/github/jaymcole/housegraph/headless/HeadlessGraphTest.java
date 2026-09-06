package io.github.jaymcole.housegraph.headless;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.GraphExecutionListener;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.graph.nodes.MissingNode;
import io.github.jaymcole.housegraph.graph.nodes.constants.ConstantFloatNode;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.headless.fixture.SignallingStartNode;
import io.github.jaymcole.housegraph.headless.fixture.UnadoptedLibraryNode;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.ui.io.SaveFileFixture;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardDataEdge;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardFlowEdge;
import io.github.jaymcole.housegraph.ui.snapshot.ClipboardNode;
import io.github.jaymcole.housegraph.ui.snapshot.GraphSnapshot;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opens real save files onto a real {@link NodeGraph} with <b>no JavaFX toolkit started</b>: no
 * canvas, no view, no {@code createNodeContent()} anywhere. That is the whole claim the headless
 * runner rests on, so these go through the same {@code GraphFileIO} → {@code GraphLoader} → resume
 * path the runner takes, rather than around it.
 *
 * <p>Nothing here sleeps. A resumed node fires immediately and the firing is observed through a
 * {@link GraphExecutionListener}, so a slow machine takes longer to satisfy the latch but never
 * reports a different result.
 */
class HeadlessGraphTest {

    /** Long enough that a loaded CI machine still gets there; each test returns as soon as it holds. */
    private static final long AWAIT_MILLIS = 10_000;

    /** The library the fixture nodes are attributed to, so a failure report has a name to print. */
    private static final String FIXTURE_PLUGIN_ID = "housegraph-fixtures";

    private static final NodeRegistry REGISTRY = new NodeRegistry(List.of(
            NodeRegistry.ScanRoot.core(HeadlessGraphTest.class.getClassLoader()),
            new NodeRegistry.ScanRoot("io.github.jaymcole.housegraph.headless.fixture",
                    HeadlessGraphTest.class.getClassLoader(), FIXTURE_PLUGIN_ID, "Fixtures", null)));

    @TempDir
    Path directory;

    private NodeGraph graph;

    @AfterEach
    void tearDown() {
        if (graph != null) {
            graph.dispose();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void resumesASavedRunningNodeOntoALiveGraphWithEveryEdgeAlreadyWired() throws IOException, InterruptedException {
        // A constant feeding an Add, and a start node whose flow output fires it. If the resume ran
        // before the load finished, the Add would either not fire or pull nothing.
        SignallingStartNode starter = new SignallingStartNode();
        starter.start();
        ConstantFloatNode constant = new ConstantFloatNode();
        constant.getOutputs().get(0).setValue(42f);

        File file = saveFixture(new GraphSnapshot(
                List.of(new ClipboardNode(starter, 0, 0),
                        new ClipboardNode(constant, 0, 60),
                        new ClipboardNode(new AddNode(), 200, 0)),
                List.of(new ClipboardDataEdge(1, 0, 2, 0, List.of())),
                List.of(new ClipboardFlowEdge(0, 0, 2, 0, List.of()))));

        graph = new NodeGraph();
        CountDownLatch added = new CountDownLatch(1);
        graph.addExecutionListener(new GraphExecutionListener() {
            @Override
            public void onNodeExecuted(BaseNode node) {
                if (node instanceof AddNode) {
                    added.countDown();
                }
            }
        });

        HeadlessGraph.Opened opened = HeadlessGraph.open(file, graph, REGISTRY, emptyCatalog());

        assertEquals(3, opened.loaded().nodes().size());
        assertEquals(1, opened.loaded().dataEdges().size(), "the data edge is wired");
        assertEquals(1, opened.loaded().flowEdges().size(), "the flow edge is wired");
        assertTrue(opened.resumeFailures().isEmpty());
        assertTrue(((SignallingStartNode) opened.loaded().nodes().get(0)).isRunning(),
                "a node saved running comes back running, with no view to hold that state");
        assertTrue(added.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS),
                "the resumed node's cascade must reach the node it is wired to");
        assertEquals(42f, opened.loaded().nodes().get(2).getOutputs().get(0).getValue(),
                "the resumed cascade pulled the input the load had already wired");
    }

    @Test
    void aNodeThatWasNotRunningWhenSavedStaysStopped() throws IOException {
        File file = saveFixture(snapshotOf(new SignallingStartNode()));

        graph = new NodeGraph();
        HeadlessGraph.Opened opened = HeadlessGraph.open(file, graph, REGISTRY, emptyCatalog());

        assertFalse(((SignallingStartNode) opened.loaded().nodes().get(0)).isRunning(),
                "the supervisor opens a graph, it never presses Start");
    }

    @Test
    void aNodeThatThrowsWhileResumingIsReportedAndCostsOnlyItself() throws IOException, InterruptedException {
        // The unadopted-library case: the throwing node is loaded first, so a pass that gave up on
        // the first failure would never reach the node that works.
        SignallingStartNode starter = new SignallingStartNode();
        starter.start();
        File file = saveFixture(new GraphSnapshot(
                List.of(new ClipboardNode(new UnadoptedLibraryNode(), 0, 0),
                        new ClipboardNode(starter, 200, 0)),
                List.of(), List.of()));

        graph = new NodeGraph();
        CountDownLatch fired = new CountDownLatch(1);
        graph.addExecutionListener(new GraphExecutionListener() {
            @Override
            public void onNodeExecuted(BaseNode node) {
                if (node instanceof SignallingStartNode) {
                    fired.countDown();
                }
            }
        });

        HeadlessGraph.Opened opened = HeadlessGraph.open(file, graph, REGISTRY, catalogNaming(FIXTURE_PLUGIN_ID, "Fixtures"));

        assertEquals(1, opened.resumeFailures().size(), "the throw is reported, not swallowed");
        HeadlessGraph.ResumeFailure failure = opened.resumeFailures().get(0);
        assertEquals("Unadopted Library", failure.node(), "the report names the node an operator sees");
        assertEquals("Fixtures (housegraph-fixtures)", failure.library(),
                "and the library that owns it, by the name in the library window and the id for the CLI");
        assertInstanceOf(NullPointerException.class, failure.cause());
        assertTrue(fired.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS),
                "the node after the failure still resumes — one bad library must not take the graph down");
    }

    @Test
    void aGraphNamingAnUninstalledLibraryStillLoadsWithPlaceholders() throws IOException {
        JSONObject root = new JSONObject();
        root.put("version", SaveFileFixture.currentVersion());
        root.put("nodes", List.of(new JSONObject(Map.of(
                "type", "com.example.NotARealNode",
                "plugin", "housegraph-widgets",
                "x", 0.0, "y", 0.0,
                "inputs", List.of(), "outputs", List.of()))));
        root.put("dataEdges", List.of());
        root.put("flowEdges", List.of());
        root.put("plugins", List.of(new JSONObject(Map.of(
                "id", "housegraph-widgets",
                "name", "Widgets",
                "version", "1.2.0",
                "repository", "https://github.com/example/housegraph-widgets"))));
        File file = write(root);

        graph = new NodeGraph();
        HeadlessGraph.Opened opened = HeadlessGraph.open(file, graph, REGISTRY, emptyCatalog());

        assertEquals(1, opened.missingLibraries().size(), "the missing library is reported");
        assertEquals("Widgets 1.2.0", opened.missingLibraries().get(0).label());
        assertInstanceOf(MissingNode.class, opened.loaded().nodes().get(0),
                "and the graph loads anyway — refusing would turn one unavailable library into a dead machine");
        assertNotNull(graph.getNodes().iterator().next(), "the placeholder is on the graph, not dropped");
    }

    // --- helpers ------------------------------------------------------------------

    private static GraphSnapshot snapshotOf(BaseNode node) {
        return new GraphSnapshot(List.of(new ClipboardNode(node, 0, 0)), List.of(), List.of());
    }

    /** Writes a snapshot through the real save path, so these fixtures are real save files. */
    private File saveFixture(GraphSnapshot snapshot) throws IOException {
        return write(SaveFileFixture.toJson(snapshot, REGISTRY));
    }

    private File write(JSONObject root) throws IOException {
        Path file = directory.resolve("graph.json");
        Files.writeString(file, root.toString(2), StandardCharsets.UTF_8);
        return file.toFile();
    }

    private PluginCatalog emptyCatalog() {
        return PluginCatalog.loadFrom(directory.resolve("plugins.json"), directory.resolve("plugins"));
    }

    private PluginCatalog catalogNaming(String id, String name) {
        PluginCatalog catalog = emptyCatalog();
        catalog.put(new PluginCatalog.Installed(id, name, "1.0.0", null, null, List.of(), "", null, true));
        return catalog;
    }
}
