package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleEntryNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleInputNode;
import io.github.jaymcole.housegraph.saveformat.GraphFileIO;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static io.github.jaymcole.housegraph.modules.ModuleFixture.REGISTRY;
import static io.github.jaymcole.housegraph.modules.ModuleFixture.graphOf;
import static io.github.jaymcole.housegraph.modules.ModuleFixture.moduleOf;
import static io.github.jaymcole.housegraph.modules.ModuleFixture.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Finding a module file by the id in its root, and assigning that id in the first place. */
class ModuleLibraryTest {

    @TempDir
    Path directory;

    @Test
    void publishingAssignsAnIdAndWritesItBackToTheFile() throws IOException {
        Path file = write("doorbell.json", graphOf(named(new ModuleEntryNode(), "Start")));
        ModuleLibrary library = ModuleLibrary.over(List.of(directory), REGISTRY);

        ModuleEntry published = library.publish(file);

        assertNotNull(published.id());
        assertEquals(published.id(), ModuleFile.idOf(GraphFileIO.readRoot(file.toFile())),
                "the id has to reach disk, or the next scan mints a different one");
        assertEquals("doorbell", published.name(), "an undeclared name falls back to the file name");
    }

    @Test
    void publishingAgainKeepsTheIdAlreadyOnDisk() throws IOException {
        Path file = write("doorbell.json", graphOf(named(new ModuleEntryNode(), "Start")));
        ModuleLibrary library = ModuleLibrary.over(List.of(directory), REGISTRY);

        assertEquals(library.publish(file).id(), library.publish(file).id());
    }

    @Test
    void aGraphWithNoBoundaryMarkersCannotBePublished() throws IOException {
        Path file = write("plain.json", graphOf(new AddNode()));
        ModuleLibrary library = ModuleLibrary.over(List.of(directory), REGISTRY);

        assertThrows(IllegalArgumentException.class, () -> library.publish(file));
    }

    @Test
    void aModuleIsFoundByIdWhereverItsFileHappensToBe() throws IOException {
        write("originally-here.json", moduleOf("m-1", named(new ModuleInputNode(), "A")));
        ModuleLibrary library = ModuleLibrary.over(List.of(directory), REGISTRY);
        String pathBefore = library.byId("m-1").orElseThrow().path();

        // Rename it: a path-based reference would break here, an id-based one must not.
        Files.move(directory.resolve("originally-here.json"), directory.resolve("moved.json"));
        library.refresh();

        Optional<ModuleEntry> found = library.byId("m-1");
        assertTrue(found.isPresent());
        assertEquals(List.of("A"), found.get().moduleInterface().ports().stream().map(ModulePort::name).toList());
        assertEquals(directory.resolve("moved.json").toAbsolutePath().toString(), found.get().path());
        assertEquals(directory.resolve("originally-here.json").toAbsolutePath().toString(), pathBefore);
    }

    @Test
    void aPathHintIsUsedOnlyWhenTheFileThereCarriesTheIdBeingLookedFor() throws IOException {
        Path outside = Files.createDirectory(directory.resolve("elsewhere"));
        Path hinted = write(outside, "hinted.json", moduleOf("m-1", named(new ModuleInputNode(), "A")));
        Path impostor = write(outside, "impostor.json", moduleOf("m-2", named(new ModuleInputNode(), "B")));
        // The library searches only `directory` itself, so neither file is in its index.
        ModuleLibrary library = ModuleLibrary.over(List.of(directory), REGISTRY);

        assertTrue(library.resolve("m-1", hinted.toString()).isPresent());
        assertTrue(library.resolve("m-1", impostor.toString()).isEmpty(),
                "a file at the hinted path carrying a different id is a different module");
        assertTrue(library.resolve("m-1", outside.resolve("gone.json").toString()).isEmpty());
    }

    @Test
    void anUnpublishedOrUnreadableFileCostsOnlyItself() throws IOException {
        write("no-id-yet.json", graphOf(named(new ModuleEntryNode(), "Start")));
        Files.writeString(directory.resolve("broken.json"), "{ not json", StandardCharsets.UTF_8);
        write("good.json", moduleOf("m-1", named(new ModuleInputNode(), "A")));

        ModuleLibrary library = ModuleLibrary.over(List.of(directory), REGISTRY);

        assertTrue(library.byId("m-1").isPresent(), "one broken file must not hide every other module");
        assertNull(library.rootOf("m-missing"));
    }

    @Test
    void aModulesOwnLibraryRequirementsTravelWithIt() throws IOException {
        JSONObject root = moduleOf("m-1", named(new ModuleInputNode(), "A"));
        root.put("plugins", new JSONArray().put(new JSONObject()
                .put("id", "housegraph-discord")
                .put("name", "Discord")
                .put("version", "0.3.1")
                .put("repository", "https://github.com/jaymcole/housegraph-discord")));
        write("discordy.json", root);

        ModuleEntry entry = ModuleLibrary.over(List.of(directory), REGISTRY).byId("m-1").orElseThrow();

        assertEquals(1, entry.requiredPlugins().size());
        assertEquals("housegraph-discord", entry.requiredPlugins().get(0).id());
        assertEquals("https://github.com/jaymcole/housegraph-discord", entry.requiredPlugins().get(0).repository());
    }

    private Path write(String name, JSONObject root) throws IOException {
        return write(directory, name, root);
    }

    private static Path write(Path into, String name, JSONObject root) throws IOException {
        Path file = into.resolve(name);
        Files.writeString(file, root.toString(2), StandardCharsets.UTF_8);
        return file;
    }
}
