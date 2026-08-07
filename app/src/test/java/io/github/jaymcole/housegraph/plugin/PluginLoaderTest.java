package io.github.jaymcole.housegraph.plugin;

import io.github.jaymcole.housegraph.graph.NodeRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginLoaderTest {

    private static final ClassLoader PARENT = PluginLoaderTest.class.getClassLoader();

    private static PluginCatalog.Installed widgets(boolean enabled) {
        return new PluginCatalog.Installed("housegraph-widgets", "Widgets", "1.0.0",
                "https://github.com/example/housegraph-widgets", "0.2",
                List.of("com.example.widgets.nodes"), "widgets", null, enabled);
    }

    private static PluginCatalog catalogWithJarOnDisk(Path temp, PluginCatalog.Installed installed) throws Exception {
        PluginCatalog catalog = PluginCatalog.loadFrom(temp.resolve("plugins.json"), temp.resolve("plugins"));
        catalog.put(installed);
        Path jar = catalog.jarFor(installed);
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(PluginManifest.ENTRY));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return catalog;
    }

    @Test
    void alwaysScansTheAppsOwnLibraryEvenWithNothingInstalled(@TempDir Path temp) {
        PluginCatalog empty = PluginCatalog.loadFrom(temp.resolve("plugins.json"), temp.resolve("plugins"));

        try (PluginLoader loader = PluginLoader.from(empty, PARENT)) {
            List<NodeRegistry.ScanRoot> roots = loader.scanRoots();

            assertEquals(1, roots.size());
            assertEquals(NodeRegistry.CORE_PLUGIN_ID, roots.get(0).pluginId());
            assertEquals(NodeRegistry.CORE_BASE_PACKAGE, roots.get(0).packageName());
        }
    }

    @Test
    void anInstalledLibraryContributesAScanRootPerDeclaredPackage(@TempDir Path temp) throws Exception {
        PluginCatalog catalog = catalogWithJarOnDisk(temp, widgets(true));

        try (PluginLoader loader = PluginLoader.from(catalog, PARENT)) {
            List<NodeRegistry.ScanRoot> roots = loader.scanRoots();

            assertEquals(2, roots.size());
            NodeRegistry.ScanRoot widgetRoot = roots.get(1);
            assertEquals("com.example.widgets.nodes", widgetRoot.packageName());
            assertEquals("housegraph-widgets", widgetRoot.pluginId());
            assertEquals("widgets", widgetRoot.categoryPrefix(), "its nodes nest under one submenu");
            assertEquals(catalog.jarFor(widgets(true)), widgetRoot.jar(),
                    "a known jar is scanned directly rather than through classpath resource enumeration");
            assertSame(loader.classLoader(), widgetRoot.loader());
        }
    }

    @Test
    void coreIsScannedFirstSoABuiltInWinsAnUnattributedTypeIdCollision(@TempDir Path temp) throws Exception {
        PluginCatalog catalog = catalogWithJarOnDisk(temp, widgets(true));

        try (PluginLoader loader = PluginLoader.from(catalog, PARENT)) {
            assertEquals(NodeRegistry.CORE_PLUGIN_ID, loader.scanRoots().get(0).pluginId());
        }
    }

    @Test
    void theLoaderDelegatesToTheAppLoaderSoThereIsOnlyEverOneSlf4jBinding(@TempDir Path temp) throws Exception {
        // Parent-first is load-bearing, not a style choice: SLF4J binds once against LoggerFactory's
        // own loader. Resolving a library's org.slf4j references to the parent's classes is what
        // keeps its logging in this app's LogManager instead of a second one with no sinks attached.
        PluginCatalog catalog = catalogWithJarOnDisk(temp, widgets(true));

        try (PluginLoader loader = PluginLoader.from(catalog, PARENT)) {
            assertSame(PARENT, loader.classLoader().getParent());
            assertSame(org.slf4j.Logger.class,
                    Class.forName("org.slf4j.Logger", false, loader.classLoader()),
                    "a library sees the host's SLF4J classes, not a copy of its own");
        }
    }

    @Test
    void aDisabledLibraryIsLeftOutOfTheLoaderEntirely(@TempDir Path temp) throws Exception {
        PluginCatalog catalog = catalogWithJarOnDisk(temp, widgets(false));

        try (PluginLoader loader = PluginLoader.from(catalog, PARENT)) {
            assertEquals(1, loader.scanRoots().size(), "only core");
        }
    }

    @Test
    void aMissingJarIsSkippedRatherThanFailingTheWholeStartup(@TempDir Path temp) {
        // The recorded jar was deleted, or an install was interrupted. Its nodes become placeholders
        // that preserve the user's graph, and the app still opens.
        PluginCatalog catalog = PluginCatalog.loadFrom(temp.resolve("plugins.json"), temp.resolve("plugins"));
        catalog.put(widgets(true));

        try (PluginLoader loader = PluginLoader.from(catalog, PARENT)) {
            assertEquals(1, loader.scanRoots().size(), "only core; the missing library contributes nothing");
            assertFalse(loader.scanRoots().stream().anyMatch(r -> r.pluginId().equals("housegraph-widgets")));
        }
    }

    @Test
    void closingReleasesTheJarSoItCanBeDeletedOrReplaced(@TempDir Path temp) throws Exception {
        // On Windows a jar held open by a class loader can be neither deleted nor overwritten, which
        // is why uninstalling closes the loader first and installs are version-stamped.
        PluginCatalog catalog = catalogWithJarOnDisk(temp, widgets(true));
        Path jar = catalog.jarFor(widgets(true));

        PluginLoader loader = PluginLoader.from(catalog, PARENT);
        Class.forName("java.lang.String", false, loader.classLoader()); // force the loader to open
        loader.close();

        assertTrue(Files.deleteIfExists(jar), "the jar must be deletable once the loader is closed");
    }
}
