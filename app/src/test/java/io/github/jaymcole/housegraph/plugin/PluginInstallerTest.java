package io.github.jaymcole.housegraph.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the validation and hashing, which are pure over a jar on disk. The download itself is a
 * thin wrapper around {@code HttpClient} and is left to manual verification.
 */
class PluginInstallerTest {

    private static Path jarWith(Path dir, String name, Map<String, String> entries) throws Exception {
        Path jar = dir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void aWellBuiltLibraryHasNoProblems(@TempDir Path temp) throws Exception {
        Path jar = jarWith(temp, "good.jar", Map.of(
                PluginManifest.ENTRY, "{}",
                "com/example/widgets/nodes/WidgetNode.class", "bytes"));

        assertEquals(List.of(), PluginInstaller.validate(jar));
    }

    @Test
    void rejectsALibraryThatBundledTheApi(@TempDir Path temp) throws Exception {
        // Its nodes would extend *its* BaseNode, so every one fails the host's isAssignableFrom
        // check during discovery and simply never appears, with nothing in the log to explain it.
        Path jar = jarWith(temp, "bundled-api.jar", Map.of(
                PluginManifest.ENTRY, "{}",
                "io/github/jaymcole/housegraph/graph/BaseNode.class", "bytes"));

        List<String> problems = PluginInstaller.validate(jar);

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("compileOnly"), "the message has to name the fix, not just the symptom");
    }

    @Test
    void rejectsALibraryThatBundledSlf4j(@TempDir Path temp) throws Exception {
        // A second SLF4J binding, initialised separately, routing into a LogManager with no sinks:
        // the library's logs vanish with no error anywhere.
        Path jar = jarWith(temp, "bundled-slf4j.jar", Map.of(
                PluginManifest.ENTRY, "{}",
                "org/slf4j/Logger.class", "bytes"));

        assertTrue(PluginInstaller.validate(jar).stream().anyMatch(p -> p.contains("slf4j")));
    }

    @Test
    void rejectsALibraryShippingItsOwnSlf4jProvider(@TempDir Path temp) throws Exception {
        Path jar = jarWith(temp, "provider.jar", Map.of(
                PluginManifest.ENTRY, "{}",
                "META-INF/services/org.slf4j.spi.SLF4JServiceProvider", "com.example.Provider"));

        assertTrue(PluginInstaller.validate(jar).stream().anyMatch(p -> p.contains("provider")));
    }

    @Test
    void reportsAFileThatIsNotAJarAtAll(@TempDir Path temp) throws Exception {
        Path notAJar = temp.resolve("nope.jar");
        Files.writeString(notAJar, "this is not a zip");

        assertFalse(PluginInstaller.validate(notAJar).isEmpty());
    }

    @Test
    void hashingDetectsASwappedCachedJar(@TempDir Path temp) throws Exception {
        Path jar = jarWith(temp, "a.jar", Map.of(PluginManifest.ENTRY, "{}"));
        String recorded = PluginInstaller.sha256(jar);

        assertTrue(PluginInstaller.matchesRecordedHash(jar, recorded));

        Path swapped = jarWith(temp, "b.jar", Map.of(PluginManifest.ENTRY, "{\"different\":true}"));
        assertFalse(PluginInstaller.matchesRecordedHash(swapped, recorded));
    }

    @Test
    void aMissingRecordedHashIsAcceptedSoOlderCatalogEntriesStillLoad(@TempDir Path temp) throws Exception {
        Path jar = jarWith(temp, "a.jar", Map.of(PluginManifest.ENTRY, "{}"));

        assertTrue(PluginInstaller.matchesRecordedHash(jar, null));
        assertTrue(PluginInstaller.matchesRecordedHash(jar, ""));
    }

    @Test
    void pruningRemovesSupersededVersionsButKeepsTheInstalledOne(@TempDir Path temp) throws Exception {
        Path pluginsRoot = temp.resolve("plugins");
        PluginCatalog catalog = PluginCatalog.loadFrom(temp.resolve("plugins.json"), pluginsRoot);
        catalog.put(new PluginCatalog.Installed("housegraph-widgets", "Widgets", "2.0.0",
                null, "0.2", List.of("a.b"), "widgets", null, true));

        Path old = pluginsRoot.resolve("housegraph-widgets").resolve("1.0.0");
        Path current = pluginsRoot.resolve("housegraph-widgets").resolve("2.0.0");
        Files.createDirectories(old);
        Files.createDirectories(current);
        Files.writeString(old.resolve("housegraph-widgets.jar"), "old");
        Files.writeString(current.resolve("housegraph-widgets.jar"), "current");

        PluginInstaller.pruneSupersededVersions(catalog);

        assertFalse(Files.exists(old), "an update leaves the previous version behind; startup is when it can go");
        assertTrue(Files.exists(current.resolve("housegraph-widgets.jar")));
    }
}
