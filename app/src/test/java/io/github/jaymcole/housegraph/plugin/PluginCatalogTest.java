package io.github.jaymcole.housegraph.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginCatalogTest {

    private static PluginCatalog.Installed widgets(String version, boolean enabled) {
        return new PluginCatalog.Installed("housegraph-widgets", "Widgets", version,
                "https://github.com/example/housegraph-widgets", "0.2",
                List.of("com.example.widgets.nodes"), "widgets", "abc123", enabled);
    }

    @Test
    void roundTripsThroughTheFile(@TempDir Path temp) {
        Path file = temp.resolve("config").resolve("plugins.json");
        PluginCatalog catalog = PluginCatalog.loadFrom(file, temp.resolve("plugins"));
        catalog.put(widgets("1.0.0", true));
        catalog.save();

        PluginCatalog reloaded = PluginCatalog.loadFrom(file, temp.resolve("plugins"));
        PluginCatalog.Installed installed = reloaded.byId("housegraph-widgets").orElseThrow();

        assertEquals("Widgets", installed.name());
        assertEquals("1.0.0", installed.version());
        assertEquals("https://github.com/example/housegraph-widgets", installed.repository());
        assertEquals(List.of("com.example.widgets.nodes"), installed.nodePackages());
        assertEquals("abc123", installed.sha256());
        assertTrue(installed.enabled());
    }

    @Test
    void anAbsentCatalogIsAnEmptyOneRatherThanAFailure(@TempDir Path temp) {
        PluginCatalog catalog = PluginCatalog.loadFrom(temp.resolve("nope.json"), temp.resolve("plugins"));
        assertTrue(catalog.all().isEmpty());
    }

    @Test
    void aCorruptCatalogLoadsEmptyRatherThanStoppingTheApp(@TempDir Path temp) throws Exception {
        // The consequence is bounded and recoverable — libraries show as not installed and can be
        // reinstalled — whereas throwing here would stop the app starting at all.
        Path file = temp.resolve("plugins.json");
        Files.writeString(file, "{ not json at all");

        assertTrue(PluginCatalog.loadFrom(file, temp.resolve("plugins")).all().isEmpty());
    }

    @Test
    void anEntryWithNoIdIsSkippedWithoutDiscardingTheRest(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("plugins.json");
        Files.writeString(file, """
                { "plugins": [
                    { "name": "no id here", "version": "1.0.0" },
                    { "id": "housegraph-widgets", "version": "1.0.0", "nodePackages": ["a.b"] }
                ] }
                """);

        PluginCatalog catalog = PluginCatalog.loadFrom(file, temp.resolve("plugins"));

        assertEquals(1, catalog.all().size());
        assertTrue(catalog.contains("housegraph-widgets"));
    }

    @Test
    void disablingKeepsTheEntryButRemovesItFromWhatGetsLoaded(@TempDir Path temp) {
        PluginCatalog catalog = PluginCatalog.loadFrom(temp.resolve("plugins.json"), temp.resolve("plugins"));
        catalog.put(widgets("1.0.0", true));

        assertTrue(catalog.setEnabled("housegraph-widgets", false));

        assertEquals(1, catalog.all().size(), "a disabled library stays installed");
        assertTrue(catalog.enabled().isEmpty(), "...but is kept out of the class loader");
        assertFalse(catalog.setEnabled("not-installed", true));
    }

    @Test
    void jarPathIsVersionStampedSoAnUpdateNeverOverwritesAJarInUse(@TempDir Path temp) {
        Path pluginsRoot = temp.resolve("plugins");
        PluginCatalog catalog = PluginCatalog.loadFrom(temp.resolve("plugins.json"), pluginsRoot);

        Path v1 = catalog.jarFor(widgets("1.0.0", true));
        Path v2 = catalog.jarFor(widgets("2.0.0", true));

        assertEquals(pluginsRoot.resolve("housegraph-widgets").resolve("1.0.0").resolve("housegraph-widgets.jar"), v1);
        assertFalse(v1.equals(v2), "a class loader holds the old jar open; the new version must land elsewhere");
    }

    @Test
    void sanitizeMatchesAppDirectoriesSoTheInstallerAndTheLookupAgree() {
        // These two must stay in step: if they diverge, jarFor() looks in a directory the installer
        // never wrote to and every library silently appears to have a missing jar.
        assertEquals("_.._etc", PluginCatalog.sanitize("/../etc"));
        assertEquals("_", PluginCatalog.sanitize(".."));
        assertEquals("_", PluginCatalog.sanitize(""));
        assertEquals("housegraph-widgets", PluginCatalog.sanitize("housegraph-widgets"));
    }

    @Test
    void savingIsAtomicSoAnInterruptedWriteCannotLeaveAHalfCatalog(@TempDir Path temp) {
        Path file = temp.resolve("plugins.json");
        PluginCatalog catalog = PluginCatalog.loadFrom(file, temp.resolve("plugins"));
        catalog.put(widgets("1.0.0", true));
        catalog.save();
        catalog.put(widgets("2.0.0", true));
        catalog.save();

        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(file.resolveSibling("plugins.json.tmp")), "the staging file is moved, not left behind");
        assertEquals("2.0.0",
                PluginCatalog.loadFrom(file, temp.resolve("plugins")).byId("housegraph-widgets").orElseThrow().version());
    }
}
