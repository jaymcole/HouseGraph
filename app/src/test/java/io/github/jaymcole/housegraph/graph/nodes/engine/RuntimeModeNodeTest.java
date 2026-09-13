package io.github.jaymcole.housegraph.graph.nodes.engine;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeModeNodeTest {

    @AfterEach
    void clearDaemonProperty() {
        System.clearProperty("housegraph.daemon");
    }

    @Test
    void reportsTrueUnderTheDaemon() {
        System.setProperty("housegraph.daemon", "true");
        RuntimeModeNode node = new RuntimeModeNode();

        node.process(ProcessContext.uncancelled());

        assertEquals(true, node.getOutputs().get(0).getValue());
    }

    @Test
    void reportsFalseOutsideTheDaemon() {
        System.clearProperty("housegraph.daemon");
        RuntimeModeNode node = new RuntimeModeNode();

        node.process(ProcessContext.uncancelled());

        assertEquals(false, node.getOutputs().get(0).getValue());
    }
}
