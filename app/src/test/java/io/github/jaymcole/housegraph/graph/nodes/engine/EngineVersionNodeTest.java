package io.github.jaymcole.housegraph.graph.nodes.engine;

import io.github.jaymcole.housegraph.AppVersion;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EngineVersionNodeTest {

    @Test
    void outputsWhatAppVersionReports() {
        EngineVersionNode node = new EngineVersionNode();

        node.process(ProcessContext.uncancelled());

        assertEquals(AppVersion.describe(), node.getOutputs().get(0).getValue());
    }
}
