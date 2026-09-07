package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleEntryNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleExitNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleNode;
import io.github.jaymcole.housegraph.plugin.PluginDirectory;
import io.github.jaymcole.housegraph.saveformat.CameraState;
import io.github.jaymcole.housegraph.saveformat.ClipboardNode;
import io.github.jaymcole.housegraph.saveformat.GraphFileIO;
import io.github.jaymcole.housegraph.saveformat.GraphSnapshot;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.jaymcole.housegraph.modules.ModuleFixture.REGISTRY;
import static io.github.jaymcole.housegraph.modules.ModuleFixture.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What may become a module, what may not, and what a user is told either way. */
class ModulePublisherTest {

    @TempDir
    Path modules;

    @TempDir
    Path elsewhere;

    @Test
    void aGraphWithBoundaryMarkersGetsAnIdAndBecomesFindable() throws IOException {
        Path file = write(modules, "doorbell.json", graphWithMarkers());
        ModuleLibrary library = library();

        ModulePublisher.Result result = ModulePublisher.publish(file, library);

        assertEquals(ModulePublisher.Outcome.PUBLISHED, result.outcome());
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        assertNotNull(result.module().id());
        assertEquals(result.module().id(), ModuleFile.idOf(GraphFileIO.readRoot(file.toFile())),
                "the id has to be on disk, or the next scan mints another one");
        assertTrue(library().byId(result.module().id()).isPresent(),
                "and a library built after the fact finds it");
    }

    @Test
    void publishingTwiceKeepsTheIdAndSaysSo() throws IOException {
        Path file = write(modules, "doorbell.json", graphWithMarkers());
        ModuleLibrary library = library();

        ModulePublisher.Result first = ModulePublisher.publish(file, library);
        ModulePublisher.Result again = ModulePublisher.publish(file, library);

        assertEquals(ModulePublisher.Outcome.PUBLISHED, first.outcome());
        assertEquals(ModulePublisher.Outcome.ALREADY_PUBLISHED, again.outcome());
        assertEquals(first.module().id(), again.module().id(),
                "a second publish must not restrand every consumer of the first");
    }

    @Test
    void aGraphWithNoBoundaryMarkersIsRefused() throws IOException {
        Path file = write(modules, "plain.json", ModuleFixture.builder(REGISTRY).with(new AddNode()).build());

        ModulePublisher.Result result = ModulePublisher.publish(file, library());

        assertEquals(ModulePublisher.Outcome.REFUSED, result.outcome());
        assertFalse(result.isPublished());
        assertNull(result.module());
        assertTrue(result.reason().contains("Module Input"), result.reason());
        assertNull(ModuleFile.idOf(GraphFileIO.readRoot(file.toFile())), "and nothing was written");
    }

    @Test
    void aFileThatIsNotAGraphIsRefusedRatherThanStamped() throws IOException {
        Path file = elsewhere.resolve("notes.json");
        Files.writeString(file, "this is not JSON at all", StandardCharsets.UTF_8);

        ModulePublisher.Result result = ModulePublisher.publish(file, library());

        assertEquals(ModulePublisher.Outcome.REFUSED, result.outcome());
        assertTrue(result.reason().contains("could not be read"), result.reason());
    }

    @Test
    void aGraphThatReferencesItselfIsRefused() throws IOException {
        Path file = write(modules, "doorbell.json", graphWithMarkers());
        ModuleLibrary library = library();
        String id = ModulePublisher.publish(file, library).module().id();

        // The module now holds a node pointing at its own id — a cycle nothing could ever load, and
        // the one thing publish is the last gate for.
        ModuleNode selfReference = new ModuleNode();
        selfReference.setModuleId(id);
        JSONObject root = GraphFileIO.toJson(new GraphSnapshot(
                        List.of(new ClipboardNode(named(new ModuleEntryNode(), "Ring"), 0.0, 0.0),
                                new ClipboardNode(selfReference, 200.0, 0.0)),
                        List.of(), List.of()),
                REGISTRY, PluginDirectory.EMPTY, library, CameraState.DEFAULT);
        root.put(ModuleFile.MODULE_KEY, new JSONObject().put("id", id));
        Files.writeString(file, root.toString(2), StandardCharsets.UTF_8);

        ModulePublisher.Result result = ModulePublisher.publish(file, library());

        assertEquals(ModulePublisher.Outcome.REFUSED, result.outcome());
        assertTrue(result.reason().contains("cycle"), result.reason());
    }

    @Test
    void aModuleOutsideTheSearchRootsIsPublishedWithAWarning() throws IOException {
        Path file = write(elsewhere, "doorbell.json", graphWithMarkers());

        ModulePublisher.Result result = ModulePublisher.publish(file, library());

        assertEquals(ModulePublisher.Outcome.PUBLISHED, result.outcome(),
                "the id is still real; it is where the file lives that is the problem");
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("after a restart"), result.warnings().toString());
    }

    @Test
    void aBoundaryConflictIsAWarningRatherThanARefusal() throws IOException {
        // Two exits sharing one name: the module resolves, and nothing can bind to that port.
        Path file = write(modules, "confused.json", ModuleFixture.builder(REGISTRY)
                .with(named(new ModuleExitNode(), "Done"), named(new ModuleExitNode(), "Done"))
                .build());

        ModulePublisher.Result result = ModulePublisher.publish(file, library());

        assertTrue(result.isPublished(), "publishing and then fixing it costs the user nothing");
        assertEquals(1, result.warnings().size(), result.warnings().toString());
    }

    @Test
    void whatIsOnTheCanvasIsCheckedBeforeAnythingIsWritten() {
        assertFalse(ModulePublisher.declaresInterface(new GraphSnapshot(
                List.of(new ClipboardNode(new AddNode(), 0.0, 0.0)), List.of(), List.of())));
        assertTrue(ModulePublisher.declaresInterface(new GraphSnapshot(
                List.of(new ClipboardNode(named(new ModuleExitNode(), "Done"), 0.0, 0.0)), List.of(), List.of())));
    }

    // --- Fixtures ---------------------------------------------------------------------------------

    private static JSONObject graphWithMarkers() {
        return ModuleFixture.builder(REGISTRY)
                .with(named(new ModuleEntryNode(), "Ring"), named(new ModuleExitNode(), "Done"))
                .build();
    }

    private ModuleLibrary library() {
        return ModuleLibrary.over(List.of(modules), REGISTRY);
    }

    private static Path write(Path directory, String name, JSONObject root) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, root.toString(2), StandardCharsets.UTF_8);
        return file;
    }
}
