package io.github.jaymcole.housegraph.graph.nodes.object;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectDecomposerNodeTest {

    record Sample(String title, int size) {
    }

    /** A minimal source node with a single output of a chosen type/value. */
    private static final class SourceNode extends BaseNode {
        final NodeVariable<Object> out;

        SourceNode(Class<?> type) {
            @SuppressWarnings("unchecked")
            NodeVariable<Object> variable = new NodeVariable<>("Out", (Class<Object>) type);
            this.out = variable;
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

    @Test
    void connectingARecordSourceExposesItsComponentsAsOutputs() {
        NodeGraph graph = new NodeGraph();
        SourceNode source = new SourceNode(Sample.class);
        ObjectDecomposerNode decomposer = new ObjectDecomposerNode();
        graph.addNode(source);
        graph.addNode(decomposer);

        graph.registerEdge(new Edge(source, source.out, decomposer, objectInput(decomposer)));

        assertEquals(List.of("title", "size"), outputNames(decomposer));
    }

    @Test
    void processPopulatesEachPropertyOutputFromTheConnectedObject() {
        NodeGraph graph = new NodeGraph();
        SourceNode source = new SourceNode(Sample.class);
        ObjectDecomposerNode decomposer = new ObjectDecomposerNode();
        graph.addNode(source);
        graph.addNode(decomposer);
        graph.registerEdge(new Edge(source, source.out, decomposer, objectInput(decomposer)));

        source.out.setValue(new Sample("hello", 7));
        decomposer.beginProcessing();

        assertEquals("hello", output(decomposer, "title").getValue());
        assertEquals(7, output(decomposer, "size").getValue());
    }

    @Test
    void removingTheInputEdgeClearsTheOutputs() {
        NodeGraph graph = new NodeGraph();
        SourceNode source = new SourceNode(Sample.class);
        ObjectDecomposerNode decomposer = new ObjectDecomposerNode();
        graph.addNode(source);
        graph.addNode(decomposer);
        Edge edge = new Edge(source, source.out, decomposer, objectInput(decomposer));
        graph.registerEdge(edge);
        assertEquals(List.of("title", "size"), outputNames(decomposer));

        graph.removeEdge(edge);

        assertTrue(outputNames(decomposer).isEmpty(), "outputs should disappear when the source is disconnected");
    }

    @Test
    void savedPropertyListRebuildsTheOutputsOnLoadWithoutAnyEdge() {
        NodeGraph graph = new NodeGraph();
        SourceNode source = new SourceNode(Sample.class);
        ObjectDecomposerNode decomposer = new ObjectDecomposerNode();
        graph.addNode(source);
        graph.addNode(decomposer);
        graph.registerEdge(new Edge(source, source.out, decomposer, objectInput(decomposer)));

        Map<String, String> state = decomposer.saveState();

        ObjectDecomposerNode loaded = new ObjectDecomposerNode();
        loaded.loadState(state);

        assertEquals(List.of("title", "size"), outputNames(loaded));
        assertEquals(String.class, output(loaded, "title").type);
        assertEquals(Integer.class, output(loaded, "size").type);
    }

    private static NodeVariable objectInput(BaseNode node) {
        return node.getInputs().get(0);
    }

    @SuppressWarnings("rawtypes")
    private static List<String> outputNames(BaseNode node) {
        List<String> names = new ArrayList<>();
        for (NodeVariable variable : node.getOutputs()) {
            names.add(variable.name);
        }
        return names;
    }

    @SuppressWarnings("rawtypes")
    private static NodeVariable output(BaseNode node, String name) {
        for (NodeVariable variable : node.getOutputs()) {
            if (variable.name.equals(name)) {
                return variable;
            }
        }
        throw new IllegalArgumentException("No output named " + name);
    }
}
