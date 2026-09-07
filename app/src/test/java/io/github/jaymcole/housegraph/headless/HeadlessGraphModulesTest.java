package io.github.jaymcole.housegraph.headless;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleEntryNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleExitNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleInputNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleOutputNode;
import io.github.jaymcole.housegraph.modules.ModuleDirectory;
import io.github.jaymcole.housegraph.modules.ModuleFixture;
import io.github.jaymcole.housegraph.modules.ModuleLibrary;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.saveformat.ClipboardNode;
import io.github.jaymcole.housegraph.saveformat.GraphSnapshot;
import io.github.jaymcole.housegraph.saveformat.SaveFileFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.jaymcole.housegraph.modules.ModuleFixture.REGISTRY;
import static io.github.jaymcole.housegraph.modules.ModuleFixture.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A supervised graph that references a module — the {@code housegraph run --headless} path, with no
 * JavaFX toolkit started.
 *
 * <p>The point of these is the ordering {@code HeadlessGraph.open} promises: a module node is bound
 * after every edge is wired and before anything resumes, so a graph the daemon opens can actually
 * run its modules rather than sitting there misconfigured.
 */
class HeadlessGraphModulesTest {

    @TempDir
    Path directory;

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
    void aSupervisedGraphBindsTheModulesItReferences() throws IOException {
        writeDoorbellModule();
        File consumer = writeConsumerReferencing("doorbell");

        graph = new NodeGraph();
        HeadlessGraph.Opened opened = HeadlessGraph.open(consumer, graph, REGISTRY, emptyCatalog(), library());

        ModuleNode module = onlyModule(opened);
        assertEquals(ModuleNode.Resolution.RESOLVED, module.getResolution());
        assertFalse(module.isMisconfigured(), "an unbound module refuses to run, so this is the whole test");
        assertEquals(1, opened.modules().bound().size());
        assertTrue(opened.modules().isComplete());
    }

    @Test
    void aGraphWhoseModuleIsNotOnThisMachineStillOpens() throws IOException {
        writeDoorbellModule();
        File consumer = writeConsumerReferencing("doorbell");

        graph = new NodeGraph();
        HeadlessGraph.Opened opened =
                HeadlessGraph.open(consumer, graph, REGISTRY, emptyCatalog(), ModuleDirectory.EMPTY);

        ModuleNode module = onlyModule(opened);
        assertEquals(ModuleNode.Resolution.UNRESOLVED, module.getResolution());
        assertEquals(1, opened.modules().unresolved().size());
        assertFalse(opened.modules().isComplete());
        assertEquals(1, opened.loaded().nodes().size(), "the graph is still open, and the node still on it");
        assertFalse(module.getModulePorts().isEmpty(), "with the ports it was saved with");
    }

    // --- Fixtures ---------------------------------------------------------------------------------

    private void writeDoorbellModule() throws IOException {
        ModuleFixture.builder(REGISTRY)
                .with(named(new ModuleEntryNode(), "Ring"),
                        named(new ModuleInputNode(), "Loudness"),
                        named(new ModuleOutputNode(), "Heard"),
                        named(new ModuleExitNode(), "Done"))
                .writeTo(modules, "doorbell");
    }

    private File writeConsumerReferencing(String id) throws IOException {
        ModuleNode node = new ModuleNode();
        node.setModuleId(id);
        assertTrue(node.bindTo(library()), "the fixture module resolves at save time");
        Path file = directory.resolve("consumer.json");
        Files.writeString(file, SaveFileFixture.toJson(
                        new GraphSnapshot(List.of(new ClipboardNode(node, 0, 0)), List.of(), List.of()), REGISTRY)
                .toString(2), StandardCharsets.UTF_8);
        return file.toFile();
    }

    private ModuleLibrary library() {
        return ModuleLibrary.over(List.of(modules), REGISTRY);
    }

    private static ModuleNode onlyModule(HeadlessGraph.Opened opened) {
        for (BaseNode node : opened.loaded().nodes()) {
            if (node instanceof ModuleNode module) {
                return module;
            }
        }
        throw new AssertionError("the fixture consumer has a module node in it");
    }

    private PluginCatalog emptyCatalog() {
        return PluginCatalog.loadFrom(directory.resolve("plugins.json"), directory.resolve("plugins"));
    }
}
