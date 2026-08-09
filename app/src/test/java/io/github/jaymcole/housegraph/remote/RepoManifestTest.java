package io.github.jaymcole.housegraph.remote;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the manifest a synced repository carries — in particular that a path in it, which arrived
 * over the network, cannot make the daemon load a graph from outside the clone.
 */
class RepoManifestTest {

    private static Path repoWith(Path root, String manifest, String... graphFiles) throws IOException {
        Files.writeString(root.resolve(RepoManifest.FILE_NAME), manifest);
        for (String graph : graphFiles) {
            Path file = root.resolve(graph);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"version\":2,\"nodes\":[],\"dataEdges\":[],\"flowEdges\":[]}");
        }
        return root;
    }

    @Test
    void readsGraphsAndPlugins() {
        RepoManifest manifest = RepoManifest.fromJson(new JSONObject("""
                { "manifestVersion": 1,
                  "graphs": [ { "file": "graphs/porch.json" }, { "file": "graphs/off.json", "enabled": false } ],
                  "plugins": [ { "id": "housegraph-camera",
                                 "repository": "https://github.com/jaymcole/housegraph-nodes",
                                 "version": "0.4.0" } ] }
                """));

        assertEquals(2, manifest.graphs().size());
        assertTrue(manifest.graphs().get(0).enabled(), "graphs are enabled unless they say otherwise");
        assertFalse(manifest.graphs().get(1).enabled());
        assertEquals("housegraph-camera", manifest.plugins().get(0).id());
        assertEquals("0.4.0", manifest.plugins().get(0).version());
    }

    @Test
    void skipsEntriesMissingTheFieldThatIdentifiesThem() {
        RepoManifest manifest = RepoManifest.fromJson(new JSONObject("""
                { "graphs": [ { "enabled": true }, { "file": "a.json" } ],
                  "plugins": [ { "version": "1" }, { "id": "housegraph-web" } ] }
                """));

        assertEquals(List.of("a.json"), manifest.graphs().stream().map(RepoManifest.GraphEntry::file).toList());
        assertEquals(1, manifest.plugins().size());
    }

    @Test
    void aRepositoryWithoutAManifestReadsAsAbsent(@TempDir Path root) {
        assertTrue(RepoManifest.read(root).isEmpty());
    }

    @Test
    void aCorruptManifestReadsAsAbsentRatherThanThrowing(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve(RepoManifest.FILE_NAME), "{ nope");

        assertTrue(RepoManifest.read(root).isEmpty(), "one broken repository must not stop the "
                + "daemon serving the others");
    }

    @Test
    void resolvesOnlyEnabledGraphsThatActuallyExist(@TempDir Path root) throws IOException {
        repoWith(root, """
                { "graphs": [ { "file": "graphs/porch.json" },
                              { "file": "graphs/off.json", "enabled": false },
                              { "file": "graphs/typo.json" } ] }
                """, "graphs/porch.json", "graphs/off.json");

        List<Path> resolved = RepoManifest.read(root).orElseThrow().resolveGraphs(root);

        assertEquals(1, resolved.size());
        assertEquals("porch.json", resolved.get(0).getFileName().toString());
    }

    @Test
    void refusesAGraphPathThatClimbsOutOfTheRepository(@TempDir Path parent) throws IOException {
        // The manifest came from the network. Without this check, "../" in it would have the daemon
        // load and execute a graph from anywhere on the disk — arbitrary code, since a graph's nodes
        // run with the user's full privileges.
        Path outside = parent.resolve("outside.json");
        Files.writeString(outside, "{}");
        Path root = Files.createDirectory(parent.resolve("clone"));
        repoWith(root, """
                { "graphs": [ { "file": "../outside.json" } ] }
                """);

        List<Path> resolved = RepoManifest.read(root).orElseThrow().resolveGraphs(root);

        assertTrue(resolved.isEmpty(), "a path escaping the clone is refused, not clamped");
    }

    @Test
    void refusesAnAbsolutePathToo(@TempDir Path root) throws IOException {
        // Path.resolve on an absolute path discards the base entirely, so this has to be caught by
        // the same containment check rather than by looking for "..".
        repoWith(root, """
                { "graphs": [ { "file": "/etc/passwd" } ] }
                """);

        assertTrue(RepoManifest.read(root).orElseThrow().resolveGraphs(root).isEmpty());
    }
}
