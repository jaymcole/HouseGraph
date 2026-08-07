package io.github.jaymcole.housegraph.plugin;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphDependencyCheckTest {

    private static PluginCatalog catalog(Path temp, PluginCatalog.Installed... installed) {
        PluginCatalog catalog = PluginCatalog.loadFrom(temp.resolve("plugins.json"), temp.resolve("plugins"));
        for (PluginCatalog.Installed entry : installed) {
            catalog.put(entry);
        }
        return catalog;
    }

    private static PluginCatalog.Installed installed(String id, String version, boolean enabled) {
        return new PluginCatalog.Installed(id, id, version, "https://github.com/example/" + id, "0.2",
                List.of("a.b"), id, null, enabled);
    }

    private static JSONObject saveNeeding(String id, String version, String repository) {
        JSONObject row = new JSONObject().put("id", id).put("name", "Widgets");
        if (version != null) {
            row.put("version", version);
        }
        if (repository != null) {
            row.put("repository", repository);
        }
        return new JSONObject().put("version", 2).put("plugins", List.of(row));
    }

    @Test
    void everythingInstalledMeansNothingToReport(@TempDir Path temp) {
        var report = GraphDependencyCheck.inspect(
                saveNeeding("housegraph-widgets", "1.0.0", null),
                catalog(temp, installed("housegraph-widgets", "1.0.0", true)));

        assertTrue(report.isSatisfied());
        assertTrue(report.blocking().isEmpty());
    }

    @Test
    void anUninstalledLibraryIsReportedWithWhereToGetIt(@TempDir Path temp) {
        var report = GraphDependencyCheck.inspect(
                saveNeeding("housegraph-widgets", "1.0.0", "https://github.com/example/housegraph-widgets"),
                catalog(temp));

        assertFalse(report.isSatisfied());
        assertEquals(1, report.missing().size());
        var required = report.missing().get(0);
        assertEquals("Widgets 1.0.0", required.label());
        assertTrue(required.isInstallable(),
                "the recorded repository is what lets the user repair the graph without typing anything");
    }

    @Test
    void aDisabledLibraryBlocksJustAsAMissingOneDoes(@TempDir Path temp) {
        var report = GraphDependencyCheck.inspect(
                saveNeeding("housegraph-widgets", "1.0.0", null),
                catalog(temp, installed("housegraph-widgets", "1.0.0", false)));

        assertFalse(report.isSatisfied());
        assertEquals(1, report.disabled().size());
        assertTrue(report.missing().isEmpty(), "it is installed; it just would not load");
    }

    @Test
    void anOlderInstalledVersionIsWorthMentioningButNotWorthBlockingFor(@TempDir Path temp) {
        // Its nodes still resolve and the graph still opens; it may just lack a newer feature. That
        // does not justify interrupting the user.
        var report = GraphDependencyCheck.inspect(
                saveNeeding("housegraph-widgets", "2.0.0", null),
                catalog(temp, installed("housegraph-widgets", "1.5.0", true)));

        assertTrue(report.isSatisfied());
        assertEquals(1, report.olderThanSaved().size());
    }

    @Test
    void aNewerInstalledVersionIsNotReported(@TempDir Path temp) {
        var report = GraphDependencyCheck.inspect(
                saveNeeding("housegraph-widgets", "1.0.0", null),
                catalog(temp, installed("housegraph-widgets", "2.0.0", true)));

        assertTrue(report.olderThanSaved().isEmpty());
    }

    @Test
    void aVersionOneFileReportsNothingBecauseItRecordedNothing(@TempDir Path temp) {
        // Documented limitation: its nodes are still preserved as placeholders, but with no
        // repository recorded there is nothing to offer. The first save under v2 fixes it.
        JSONObject legacy = new JSONObject().put("version", 1);

        var report = GraphDependencyCheck.inspect(legacy, catalog(temp));

        assertTrue(report.isSatisfied());
    }

    @Test
    void aRepositoryOnANonGitHubHostIsNotOfferedAsInstallable(@TempDir Path temp) {
        // A save file is untrusted input proposing a code download; the offer is gated on the same
        // host restriction the fetcher enforces.
        var report = GraphDependencyCheck.inspect(
                saveNeeding("housegraph-widgets", "1.0.0", "https://evil.example.com/a/b"),
                catalog(temp));

        assertFalse(report.missing().get(0).isInstallable());
    }

    @Test
    void duplicateAndMalformedRowsAreIgnoredWithoutLosingTheRest(@TempDir Path temp) {
        JSONObject root = new JSONObject().put("version", 2).put("plugins", List.of(
                new JSONObject().put("id", "housegraph-widgets"),
                new JSONObject().put("id", "housegraph-widgets"),
                new JSONObject().put("name", "no id"),
                new JSONObject().put("id", "housegraph-other")));

        var report = GraphDependencyCheck.inspect(root, catalog(temp));

        assertEquals(2, report.missing().size());
    }

    // --- Version comparison ---------------------------------------------------------------------

    @Test
    void versionComparisonHandlesTheOrdinaryCases() {
        assertTrue(GraphDependencyCheck.isOlder("1.0.0", "1.0.1"));
        assertTrue(GraphDependencyCheck.isOlder("1.9.0", "1.10.0"), "numeric, not lexicographic");
        assertTrue(GraphDependencyCheck.isOlder("1.0", "1.0.1"), "a missing part counts as zero");
        assertFalse(GraphDependencyCheck.isOlder("2.0.0", "1.0.0"));
        assertFalse(GraphDependencyCheck.isOlder("1.0.0", "1.0.0"));
    }

    @Test
    void anUnreadableVersionStaysQuietRatherThanRaisingAFalseAlarm() {
        // This only drives an advisory message, so a scheme it cannot parse should say nothing.
        assertFalse(GraphDependencyCheck.isOlder("nightly", "1.0.0"));
        assertFalse(GraphDependencyCheck.isOlder("1.0.0", "nightly"));
        assertFalse(GraphDependencyCheck.isOlder(null, "1.0.0"));
        assertFalse(GraphDependencyCheck.isOlder("", "1.0.0"));
    }
}
