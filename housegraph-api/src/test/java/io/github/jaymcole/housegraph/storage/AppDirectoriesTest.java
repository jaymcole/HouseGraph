package io.github.jaymcole.housegraph.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppDirectoriesTest {

    // --- resolveRoot: pure OS logic, tested for every branch on any host OS -------

    @Test
    void windowsUsesAppData() {
        Path root = AppDirectories.resolveRoot(
                "Windows 11", env(Map.of("APPDATA", "C:\\Users\\jay\\AppData\\Roaming")), "C:\\Users\\jay", null);
        assertEquals(Path.of("C:\\Users\\jay\\AppData\\Roaming").resolve("HouseGraph"), root);
    }

    @Test
    void windowsFallsBackToRoamingUnderUserHomeWhenAppDataMissing() {
        Path root = AppDirectories.resolveRoot("Windows 10", env(Map.of()), "C:\\Users\\jay", null);
        assertEquals(Path.of("C:\\Users\\jay", "AppData", "Roaming", "HouseGraph"), root);
    }

    @Test
    void macUsesApplicationSupport() {
        Path root = AppDirectories.resolveRoot("Mac OS X", env(Map.of()), "/Users/jay", null);
        assertEquals(Path.of("/Users/jay", "Library", "Application Support", "HouseGraph"), root);
    }

    @Test
    void linuxUsesXdgDataHomeWhenSet() {
        Path root = AppDirectories.resolveRoot(
                "Linux", env(Map.of("XDG_DATA_HOME", "/home/jay/.local/share")), "/home/jay", null);
        assertEquals(Path.of("/home/jay/.local/share").resolve("HouseGraph"), root);
    }

    @Test
    void linuxFallsBackToLocalShareWhenXdgUnset() {
        Path root = AppDirectories.resolveRoot("Linux", env(Map.of()), "/home/jay", null);
        assertEquals(Path.of("/home/jay", ".local", "share", "HouseGraph"), root);
    }

    @Test
    void explicitOverrideWinsOverOsDefaults() {
        Path root = AppDirectories.resolveRoot(
                "Windows 11", env(Map.of("APPDATA", "C:\\ignored")), "C:\\Users\\jay", "D:\\portable\\hg");
        assertEquals(Path.of("D:\\portable\\hg"), root);
    }

    @Test
    void blankOverrideIsIgnored() {
        Path root = AppDirectories.resolveRoot("Linux", env(Map.of()), "/home/jay", "   ");
        assertEquals(Path.of("/home/jay", ".local", "share", "HouseGraph"), root);
    }

    // --- Directory accessors: real filesystem, rooted at a temp dir ---------------

    @Test
    void createsEachSubdirectoryOnDemandUnderTheRoot(@TempDir Path temp) {
        AppDirectories dirs = new AppDirectories(temp.resolve("HouseGraph"));

        assertEquals(temp.resolve("HouseGraph").resolve("secrets"), dirs.secrets());
        assertTrue(Files.isDirectory(dirs.secrets()));
        assertTrue(Files.isDirectory(dirs.nodes()));
        assertTrue(Files.isDirectory(dirs.plugins()));
        assertTrue(Files.isDirectory(dirs.saves()));
        assertTrue(Files.isDirectory(dirs.config()));
        assertTrue(Files.isDirectory(dirs.cache()));
        assertTrue(Files.isDirectory(dirs.logs()));
    }

    @Test
    void nodeStorageKeepsSanitisedKeysUnderTheNodesDir(@TempDir Path temp) {
        AppDirectories dirs = new AppDirectories(temp.resolve("HouseGraph"));

        Path escapeAttempt = dirs.nodeStorage("../../etc");
        assertTrue(escapeAttempt.startsWith(dirs.nodes()), "a key with separators must not climb out of nodes/");
        assertEquals(dirs.nodes().resolve("_"), dirs.nodeStorage(".."), "a pure traversal key collapses to a safe segment");
    }

    @Test
    void pluginJarIsVersionStampedSoAnInstalledJarIsNeverOverwrittenInPlace(@TempDir Path temp) {
        AppDirectories dirs = new AppDirectories(temp.resolve("HouseGraph"));

        Path first = dirs.pluginJar("housegraph-discord", "0.1.0");
        Path second = dirs.pluginJar("housegraph-discord", "0.2.0");

        assertEquals(dirs.plugins().resolve("housegraph-discord").resolve("0.1.0").resolve("housegraph-discord.jar"), first);
        assertTrue(Files.isDirectory(first.getParent()), "the version directory is created, ready for the download");
        assertEquals(first.getFileName(), second.getFileName(), "both versions use the same jar name");
        assertTrue(Files.isDirectory(second.getParent()), "a second version installs alongside, not over, the first");
    }

    @Test
    void pluginJarSanitisesBothSegmentsSoAManifestCannotEscapeThePluginsDir(@TempDir Path temp) {
        AppDirectories dirs = new AppDirectories(temp.resolve("HouseGraph"));

        Path idEscape = dirs.pluginJar("../../evil", "1.0.0");
        Path versionEscape = dirs.pluginJar("plugin", "../../etc");

        assertTrue(idEscape.startsWith(dirs.plugins()), "a plugin id with separators must not climb out of plugins/");
        assertTrue(versionEscape.startsWith(dirs.plugins()), "a version with separators must not climb out of plugins/");
        assertEquals(dirs.plugins().resolve("plugin").resolve("_"), dirs.pluginJar("plugin", "..").getParent(),
                "a pure traversal version collapses to a safe segment");
    }

    private static UnaryOperator<String> env(Map<String, String> values) {
        return values::get;
    }

    // --- The graph folder is the one directory that can be moved -------------------

    @Test
    void savesDefaultsToTheSavesDirectoryUnderTheRoot(@TempDir Path dir) {
        AppDirectories directories = new AppDirectories(dir);

        assertEquals(dir.resolve("saves"), directories.saves());
        assertEquals(dir.resolve("saves"), directories.defaultSaves());
        assertNull(directories.savesOverride(), "nothing is overridden until it is set");
    }

    @Test
    void anOverrideMovesOnlyTheGraphFolder(@TempDir Path dir, @TempDir Path elsewhere) {
        AppDirectories directories = new AppDirectories(dir);
        Path graphs = elsewhere.resolve("my-graphs");

        directories.setSaves(graphs);

        assertEquals(graphs, directories.saves());
        assertTrue(Files.isDirectory(graphs), "the chosen folder is created when it is set");
        // The whole reason this is not HOUSEGRAPH_HOME: the secret key, the plugin jars and the
        // logs must not follow the user's documents out of the app directory.
        assertEquals(dir.resolve("secrets"), directories.secrets());
        assertEquals(dir.resolve("plugins"), directories.plugins());
        assertEquals(dir.resolve("logs"), directories.logs());
        assertEquals(dir.resolve("config"), directories.config());
    }

    @Test
    void aNullOverrideReturnsToTheDefault(@TempDir Path dir, @TempDir Path elsewhere) {
        AppDirectories directories = new AppDirectories(dir);
        directories.setSaves(elsewhere.resolve("my-graphs"));

        directories.setSaves(null);

        assertEquals(dir.resolve("saves"), directories.saves());
        assertNull(directories.savesOverride());
    }

    @Test
    void anOverrideIsStoredAbsoluteAndNormalised(@TempDir Path dir, @TempDir Path elsewhere) {
        AppDirectories directories = new AppDirectories(dir);

        directories.setSaves(elsewhere.resolve("nested").resolve("..").resolve("graphs"));

        assertEquals(elsewhere.resolve("graphs"), directories.savesOverride());
    }

    @Test
    void anUnusableOverrideIsRejectedWhenItIsSet(@TempDir Path dir) throws java.io.IOException {
        AppDirectories directories = new AppDirectories(dir);
        // A regular file where a directory is wanted: createDirectories cannot make this work, and
        // the failure belongs here — while the user is choosing — not at their next save.
        Path file = dir.resolve("not-a-folder");
        Files.writeString(file, "x");

        assertThrows(UncheckedIOException.class, () -> directories.setSaves(file));
        assertEquals(dir.resolve("saves"), directories.saves(), "a rejected choice changes nothing");
    }
}
