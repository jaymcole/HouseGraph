package io.github.jaymcole.housegraph.graph.nodes.engine;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.storage.AppDirectories;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppDataDirectoryNodeTest {

    @Test
    void outputsTheSharedAppDirectoriesRoot() {
        AppDataDirectoryNode node = new AppDataDirectoryNode();

        node.process(ProcessContext.uncancelled());

        assertEquals(AppDirectories.get().root().toString(), node.getOutputs().get(0).getValue());
    }
}
