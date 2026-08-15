package io.github.jaymcole.housegraph.remote;

import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The manifest-versus-catalog decision, tested without a network, a clone or a real catalog file.
 *
 * <p>Worth pinning because it is the whole of what changed when {@code PluginEntry.version} stopped
 * being ignored: before, anything already installed was skipped unconditionally, so a version bump in
 * a repository's {@code housegraph.json} did nothing at all.
 */
class RemoteDeploymentTest {

    private static RepoManifest.PluginEntry entry(String version) {
        return new RepoManifest.PluginEntry("housegraph-widgets",
                "https://github.com/example/housegraph-widgets", version);
    }

    private static PluginCatalog.Installed installed(String version) {
        return new PluginCatalog.Installed("housegraph-widgets", "Widgets", version,
                "https://github.com/example/housegraph-widgets", "0.2", List.of("a.b"), "widgets", null, true);
    }

    @Test
    void aLibraryThatIsNotInstalledIsInstalled() {
        assertEquals(RemoteDeployment.PluginAction.INSTALL,
                RemoteDeployment.decide(entry("1.0.0"), null));
    }

    @Test
    void anInstalledLibraryBehindTheManifestIsUpdated() {
        assertEquals(RemoteDeployment.PluginAction.UPDATE,
                RemoteDeployment.decide(entry("1.2.0"), installed("1.0.0")));
    }

    @Test
    void anInstalledLibraryAtOrAheadOfTheManifestIsLeftAlone() {
        assertEquals(RemoteDeployment.PluginAction.SKIP,
                RemoteDeployment.decide(entry("1.0.0"), installed("1.0.0")));
        assertEquals(RemoteDeployment.PluginAction.SKIP,
                RemoteDeployment.decide(entry("1.0.0"), installed("1.4.0")));
    }

    @Test
    void aManifestWithNoVersionInstallsOnceAndNeverMovesAgain() {
        assertEquals(RemoteDeployment.PluginAction.INSTALL, RemoteDeployment.decide(entry(null), null));
        assertEquals(RemoteDeployment.PluginAction.SKIP, RemoteDeployment.decide(entry(null), installed("1.0.0")));
    }

    @Test
    void anUnparseableVersionCausesNoUpdate() {
        // A false positive here downloads a jar and restarts a graph for nothing, so a version scheme
        // the comparison can't read must produce silence rather than a guess.
        assertEquals(RemoteDeployment.PluginAction.SKIP,
                RemoteDeployment.decide(entry("nightly"), installed("1.0.0")));
        assertEquals(RemoteDeployment.PluginAction.SKIP,
                RemoteDeployment.decide(entry("1.0.0"), installed("nightly")));
    }
}
