package io.github.jaymcole.housegraph.plugin;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginManifestTest {

    private static JSONObject validJson() {
        return new JSONObject()
                .put("manifestVersion", 1)
                .put("id", "housegraph-widgets")
                .put("name", "Widgets")
                .put("version", "1.2.3")
                .put("apiVersion", "0.2")
                .put("repository", "https://github.com/example/housegraph-widgets")
                .put("nodePackages", List.of("com.example.widgets.nodes"));
    }

    @Test
    void parsesAWellFormedManifest() {
        PluginManifest manifest = PluginManifest.parse(validJson()).orElseThrow();

        assertEquals("housegraph-widgets", manifest.id());
        assertEquals("Widgets", manifest.name());
        assertEquals("1.2.3", manifest.version());
        assertEquals(List.of("com.example.widgets.nodes"), manifest.nodePackages());
        assertEquals("https://github.com/example/housegraph-widgets", manifest.repository());
    }

    @Test
    void categoryPrefixDefaultsToTheIdSoNodesGroupUnderOneSubmenu() {
        assertEquals("housegraph-widgets", PluginManifest.parse(validJson()).orElseThrow().categoryPrefix());
        assertEquals("widgets",
                PluginManifest.parse(validJson().put("categoryPrefix", "widgets")).orElseThrow().categoryPrefix());
    }

    @Test
    void rejectsAnIdThatCouldNotSurviveBeingADirectoryName() {
        // The id is used as a path segment and a save-file key, so it is deliberately narrow.
        assertTrue(PluginManifest.parse(validJson().put("id", "../escape")).isEmpty());
        assertTrue(PluginManifest.parse(validJson().put("id", "Has Spaces")).isEmpty());
        assertTrue(PluginManifest.parse(validJson().put("id", "UPPER")).isEmpty());
        assertTrue(PluginManifest.parse(validJson().put("id", "")).isEmpty());
    }

    @Test
    void rejectsAManifestWithNothingToScan() {
        assertTrue(PluginManifest.parse(validJson().put("nodePackages", List.of())).isEmpty(),
                "a library declaring no packages could never contribute a node, so it is not loadable");
    }

    @Test
    void rejectsAManifestWithNoVersion() {
        assertTrue(PluginManifest.parse(validJson().put("version", "")).isEmpty(),
                "the version is the install directory name and the update comparison; it cannot be absent");
    }

    @Test
    void rejectsANewerManifestSchemaRatherThanGuessing() {
        assertTrue(PluginManifest.parse(validJson().put("manifestVersion", 99)).isEmpty());
    }

    @Test
    void repositoryIsNullRatherThanBlankWhenAbsent() {
        JSONObject json = validJson();
        json.remove("repository");
        assertNull(PluginManifest.parse(json).orElseThrow().repository());
    }

    // --- Reading from a real jar ---------------------------------------------------------------

    @Test
    void readsTheManifestFromAJarWithoutLoadingAnyClassFromIt(@TempDir Path temp) throws Exception {
        Path jar = temp.resolve("widgets.jar");
        writeJar(jar, PluginManifest.ENTRY, validJson().toString());

        PluginManifest manifest = PluginManifest.read(jar).orElseThrow();
        assertEquals("housegraph-widgets", manifest.id());
    }

    @Test
    void aJarWithNoManifestIsSimplyNotANodeLibrary(@TempDir Path temp) throws Exception {
        Path jar = temp.resolve("plain.jar");
        writeJar(jar, "com/example/Thing.class", "not really a class");

        assertEquals(Optional.empty(), PluginManifest.read(jar));
    }

    @Test
    void aCorruptManifestIsReportedRatherThanThrown(@TempDir Path temp) throws Exception {
        Path jar = temp.resolve("broken.jar");
        writeJar(jar, PluginManifest.ENTRY, "{ this is not json");

        assertEquals(Optional.empty(), PluginManifest.read(jar),
                "a corrupt manifest must not take down whatever was enumerating jars");
    }

    static void writeJar(Path jar, String entryName, String content) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
