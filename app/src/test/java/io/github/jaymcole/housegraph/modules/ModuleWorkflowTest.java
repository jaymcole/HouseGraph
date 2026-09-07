package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.graph.nodes.module.ModuleEntryNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleExitNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleInputNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleOutputNode;
import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck;
import io.github.jaymcole.housegraph.plugin.PluginDirectory;
import io.github.jaymcole.housegraph.saveformat.CameraState;
import io.github.jaymcole.housegraph.saveformat.ClipboardNode;
import io.github.jaymcole.housegraph.saveformat.GraphFileIO;
import io.github.jaymcole.housegraph.saveformat.GraphSnapshot;
import org.json.JSONArray;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publish a module, point a node at it, save the consumer — the three steps the desktop performs in
 * that order, against a real {@link ModuleLibrary} rather than a stubbed directory.
 *
 * <p>The step worth having a test of its own is the last one. A consumer's {@code plugins} table is
 * derived from its own nodes and can never mention a library that only the module uses, so the
 * module's own requirements are copied into the consumer's {@code modules} row at save time. That
 * only happens when the save is handed a directory that can answer — which is exactly what
 * {@code ModuleDirectory.EMPTY} cannot, and what the editor passed until now.
 */
class ModuleWorkflowTest {

    @TempDir
    Path modules;

    @Test
    void aConsumerSavedAgainstARealLibraryRecordsWhatItsModuleNeeds() throws IOException {
        String id = publishDoorbellNeeding("housegraph-discord", "Discord", "0.3.1");
        ModuleLibrary library = library();

        ModuleNode node = new ModuleNode();
        node.setModuleId(id);
        assertTrue(node.bindTo(library), "the module was just published, so it resolves");
        assertFalse(node.isMisconfigured());

        JSONObject consumer = save(node, library);

        JSONObject row = consumer.getJSONArray(ModuleFile.MODULES_KEY).getJSONObject(0);
        assertEquals(id, row.getString("id"));
        assertEquals("doorbell", row.getString("name"));
        JSONArray needs = row.getJSONArray("plugins");
        assertEquals(1, needs.length());
        assertEquals("housegraph-discord", needs.getJSONObject(0).getString("id"));

        assertFalse(consumer.has("plugins"),
                "the consumer's own nodes need nothing, which is the whole reason the row has to carry it");
        assertEquals(List.of("Discord 0.3.1"),
                GraphDependencyCheck.requiredBy(consumer).stream()
                        .map(GraphDependencyCheck.RequiredPlugin::label).toList(),
                "so opening this graph elsewhere offers to install the library the module needs");
    }

    @Test
    void withNoLibraryToHandTheSameSaveDegradesToWhatTheNodeRemembers() throws IOException {
        String id = publishDoorbellNeeding("housegraph-discord", "Discord", "0.3.1");
        ModuleNode node = new ModuleNode();
        node.setModuleId(id);
        node.bindTo(library());

        JSONObject consumer = save(node, ModuleDirectory.EMPTY);

        JSONObject row = consumer.getJSONArray(ModuleFile.MODULES_KEY).getJSONObject(0);
        assertEquals(id, row.getString("id"));
        assertFalse(row.has("plugins"),
                "a machine without the module file must still write the reference back, and claim nothing more");
    }

    @Test
    void aPublishedModuleIsWhatThePickerThenOffers() throws IOException {
        String id = publishDoorbellNeeding("housegraph-discord", "Discord", "0.3.1");

        List<ModuleChoices.Choice> offered = ModuleChoices.offer(library().all(), null);

        assertEquals(1, offered.size());
        assertEquals(id, offered.get(0).id());
        assertEquals("1 in, 1 out · 1 entry, 1 exit", offered.get(0).summary());
        assertEquals(List.of("Discord 0.3.1"), offered.get(0).needs());
    }

    // --- Fixtures ---------------------------------------------------------------------------------

    /**
     * A published module whose file names one node library. The {@code plugins} table is put in by
     * hand rather than by using a fixture node from a fake scan root, because what is under test is
     * the copy from the module's table into the consumer's row, and the table is plain data either way.
     *
     * @return the id the publish minted
     */
    private String publishDoorbellNeeding(String pluginId, String name, String version) throws IOException {
        JSONObject root = ModuleFixture.builder(REGISTRY)
                .with(named(new ModuleEntryNode(), "Ring"),
                        named(new ModuleInputNode(), "Loudness"),
                        named(new ModuleOutputNode(), "Heard"),
                        named(new ModuleExitNode(), "Done"))
                .build();
        root.put("plugins", new JSONArray().put(new JSONObject()
                .put("id", pluginId).put("name", name).put("version", version)));
        Path file = modules.resolve("doorbell.json");
        Files.writeString(file, root.toString(2), StandardCharsets.UTF_8);

        ModulePublisher.Result published = ModulePublisher.publish(file, library());
        assertTrue(published.isPublished(), published.reason());
        return published.module().id();
    }

    private static JSONObject save(ModuleNode node, ModuleDirectory directory) {
        return GraphFileIO.toJson(
                new GraphSnapshot(List.of(new ClipboardNode(node, 0.0, 0.0)), List.of(), List.of()),
                REGISTRY, PluginDirectory.EMPTY, directory, CameraState.DEFAULT);
    }

    private ModuleLibrary library() {
        return ModuleLibrary.over(List.of(modules), REGISTRY);
    }
}
