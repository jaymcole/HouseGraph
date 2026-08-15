package io.github.jaymcole.housegraph.remote;

import io.github.jaymcole.housegraph.plugin.AutoInstallPlan;
import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck;
import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck.RequiredPlugin;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daemon's requirement-gathering and install decision, without a network, a clone or a real
 * catalog on disk.
 *
 * <p>These exercise the same three pieces {@code RemoteDeployment.installDeclaredPlugins} composes —
 * gather in precedence order, {@link GraphDependencyCheck#classify}, {@link AutoInstallPlan#from} —
 * because the method itself needs a git repository to run and the composition is where the behaviour
 * lives.
 */
class RemoteDeploymentTest {

    private static final String REPOSITORY = "https://github.com/example/housegraph-widgets";

    private static PluginCatalog catalog(Path temp, PluginCatalog.Installed... installed) {
        PluginCatalog catalog = PluginCatalog.loadFrom(temp.resolve("plugins.json"), temp.resolve("plugins"));
        for (PluginCatalog.Installed entry : installed) {
            catalog.put(entry);
        }
        return catalog;
    }

    private static PluginCatalog.Installed installed(String version, boolean enabled) {
        return new PluginCatalog.Installed("housegraph-widgets", "Widgets", version, REPOSITORY,
                "0.2", List.of("a.b"), "widgets", null, enabled);
    }

    /** A manifest entry, as {@code requirementsOf} converts it. */
    private static RequiredPlugin fromManifest(String version) {
        return new RequiredPlugin("housegraph-widgets", "housegraph-widgets", version, REPOSITORY);
    }

    /** A save file naming the same library at some version. */
    private static JSONObject savedGraph(String version) {
        return new JSONObject().put("version", 2).put("plugins", List.of(
                new JSONObject().put("id", "housegraph-widgets")
                        .put("version", version)
                        .put("repository", REPOSITORY)));
    }

    /** Manifest first, then graphs — the precedence order the daemon builds. */
    private static List<RequiredPlugin> gather(List<RequiredPlugin> manifest, JSONObject... graphs) {
        List<RequiredPlugin> required = new ArrayList<>(manifest);
        for (JSONObject graph : graphs) {
            required.addAll(GraphDependencyCheck.requiredBy(graph));
        }
        return required;
    }

    private static AutoInstallPlan plan(List<RequiredPlugin> required, PluginCatalog catalog) {
        return AutoInstallPlan.from(GraphDependencyCheck.classify(required, catalog), url -> true);
    }

    @Test
    void aLibraryOnlyASaveFileNamesIsStillInstalled(@TempDir Path temp) {
        // The whole point of the reversal: no plugins[] entry in the manifest at all.
        AutoInstallPlan plan = plan(gather(List.of(), savedGraph("1.0.0")), catalog(temp));

        assertEquals(1, plan.actions().size());
        assertEquals(AutoInstallPlan.Kind.INSTALL, plan.actions().get(0).kind());
        assertEquals(REPOSITORY, plan.actions().get(0).repository());
    }

    @Test
    void theManifestWinsWhenItAndASaveFileDisagree(@TempDir Path temp) {
        // Manifest says 2.0.0, the graph was saved against 1.0.0, and 1.0.0 is installed. The
        // manifest's floor is the one that counts, so this is an update rather than a skip.
        AutoInstallPlan plan = plan(
                gather(List.of(fromManifest("2.0.0")), savedGraph("1.0.0")),
                catalog(temp, installed("1.0.0", true)));

        assertEquals(1, plan.actions().size());
        assertEquals(AutoInstallPlan.Kind.UPDATE, plan.actions().get(0).kind());
    }

    @Test
    void theManifestWinsInTheQuietDirectionToo(@TempDir Path temp) {
        // Reverse of the above: the graph records a newer version than the manifest asks for. The
        // manifest is a statement of intent; the save file's number is whatever the authoring
        // machine happened to have, so it must not trigger an update on its own.
        AutoInstallPlan plan = plan(
                gather(List.of(fromManifest("1.0.0")), savedGraph("9.9.9")),
                catalog(temp, installed("1.0.0", true)));

        assertFalse(plan.hasActions());
    }

    @Test
    void oneLibraryNamedByManyGraphsIsInstalledOnce(@TempDir Path temp) {
        AutoInstallPlan plan = plan(
                gather(List.of(), savedGraph("1.0.0"), savedGraph("1.0.0"), savedGraph("1.0.0")),
                catalog(temp));

        assertEquals(1, plan.actions().size());
    }

    @Test
    void anUpToDateLibraryIsLeftAlone(@TempDir Path temp) {
        AutoInstallPlan plan = plan(
                gather(List.of(fromManifest("1.0.0")), savedGraph("1.0.0")),
                catalog(temp, installed("1.4.0", true)));

        assertFalse(plan.hasActions());
    }

    @Test
    void aDisabledLibraryIsReportedRatherThanReEnabled(@TempDir Path temp) {
        AutoInstallPlan plan = plan(
                gather(List.of(), savedGraph("1.0.0")),
                catalog(temp, installed("1.0.0", false)));

        assertFalse(plan.hasActions());
        assertEquals(1, plan.refused().size());
    }

    @Test
    void anUnparseableVersionCausesNoUpdate(@TempDir Path temp) {
        // A false positive downloads a jar and restarts a graph for nothing, so a version scheme the
        // comparison can't read must produce silence rather than a guess.
        AutoInstallPlan plan = plan(
                gather(List.of(fromManifest("nightly"))),
                catalog(temp, installed("1.0.0", true)));

        assertFalse(plan.hasActions());
    }

    @Test
    void nothingIsInstalledWhenTheGateIsShut(@TempDir Path temp) {
        RemoteConfig off = RemoteConfig.fromJson(new JSONObject("{ \"allowPluginInstall\": false }"));

        AutoInstallPlan plan = AutoInstallPlan.from(
                GraphDependencyCheck.classify(gather(List.of(), savedGraph("1.0.0")), catalog(temp)),
                off::isTrustedForInstall);

        assertFalse(plan.hasActions());
        assertTrue(plan.refused().stream().anyMatch(refused -> refused.id().equals("housegraph-widgets")));
    }
}
