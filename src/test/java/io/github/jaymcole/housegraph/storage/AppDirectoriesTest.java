package io.github.jaymcole.housegraph.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(Files.isDirectory(dirs.saves()));
        assertTrue(Files.isDirectory(dirs.config()));
        assertTrue(Files.isDirectory(dirs.cache()));
    }

    @Test
    void nodeStorageKeepsSanitisedKeysUnderTheNodesDir(@TempDir Path temp) {
        AppDirectories dirs = new AppDirectories(temp.resolve("HouseGraph"));

        Path escapeAttempt = dirs.nodeStorage("../../etc");
        assertTrue(escapeAttempt.startsWith(dirs.nodes()), "a key with separators must not climb out of nodes/");
        assertEquals(dirs.nodes().resolve("_"), dirs.nodeStorage(".."), "a pure traversal key collapses to a safe segment");
    }

    private static UnaryOperator<String> env(Map<String, String> values) {
        return values::get;
    }
}
