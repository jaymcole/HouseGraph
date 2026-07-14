package io.github.jaymcole.housegraph.graph.nodes.web;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.store.JsonDocumentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies the web-server node captures the data-store handle off its {@code Store} data edge
 * when it starts — the pull-at-Start wiring that replaced the old name-reference. Uses a
 * synthetic upstream source so it stays headless and off disk.
 */
class WebServerNodeTest {

    /** A minimal data node that outputs a fixed store handle — stands in for {@code DataStoreNode}. */
    private static final class StoreSource extends BaseNode {
        private final NodeVariable<JsonDocumentStore> out =
                new NodeVariable<>("Store", JsonDocumentStore.class).transientValue();
        private final JsonDocumentStore store;

        StoreSource(JsonDocumentStore store) {
            this.store = store;
        }

        @Override
        public void process(ProcessContext ctx) {
            out.setValue(store);
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
    void capturesWiredStoreWhenResolved(@TempDir Path dir) {
        NodeGraph graph = new NodeGraph();
        JsonDocumentStore store = new JsonDocumentStore(dir.resolve("document.json"));
        StoreSource source = new StoreSource(store);
        WebServerNode web = new WebServerNode();
        graph.addNode(source);
        graph.addNode(web);
        graph.registerEdge(new Edge(source, source.out, web, web.getInputs().get(0)));

        web.beginProcessing();

        assertSame(store, web.resolvedStore(), "web server should capture the store handle off its input edge");
    }

    @Test
    void noStoreWiredLeavesCaptureNull() {
        NodeGraph graph = new NodeGraph();
        WebServerNode web = new WebServerNode();
        graph.addNode(web);

        web.beginProcessing();

        assertNull(web.resolvedStore(), "with nothing wired the server serves static-only");
    }
}
