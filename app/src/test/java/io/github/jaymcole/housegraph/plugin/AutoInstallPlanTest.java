package io.github.jaymcole.housegraph.plugin;

import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck.DependencyReport;
import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck.RequiredPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoInstallPlanTest {

    private static final String REPOSITORY = "https://github.com/example/housegraph-widgets";

    private static RequiredPlugin required(String id, String repository) {
        return new RequiredPlugin(id, id, "1.2.0", repository);
    }

    /** A trust store with auto-install on and the given repositories accepted. */
    private static PluginTrust trusting(Path temp, String... repositories) {
        PluginTrust trust = PluginTrust.loadFrom(temp.resolve("plugin-trust.json"));
        trust.setAutoInstallEnabled(true);
        for (String repository : repositories) {
            trust.trust(repository);
        }
        return trust;
    }

    @Test
    void aMissingLibraryFromATrustedRepositoryIsInstalled(@TempDir Path temp) {
        DependencyReport report = new DependencyReport(
                List.of(required("housegraph-widgets", REPOSITORY)), List.of(), List.of());

        AutoInstallPlan plan = AutoInstallPlan.from(report, trusting(temp, REPOSITORY));

        assertEquals(1, plan.actions().size());
        assertEquals(new AutoInstallPlan.Action("housegraph-widgets", REPOSITORY, AutoInstallPlan.Kind.INSTALL),
                plan.actions().get(0));
        assertTrue(plan.needsConfirmation().isEmpty());
    }

    @Test
    void anOutOfDateLibraryFromATrustedRepositoryIsUpdated(@TempDir Path temp) {
        DependencyReport report = new DependencyReport(
                List.of(), List.of(), List.of(required("housegraph-widgets", REPOSITORY)));

        AutoInstallPlan plan = AutoInstallPlan.from(report, trusting(temp, REPOSITORY));

        assertEquals(AutoInstallPlan.Kind.UPDATE, plan.actions().get(0).kind());
        // Out of date is advisory: its nodes still resolve, so there is nothing to interrupt about.
        assertTrue(plan.needsConfirmation().isEmpty());
    }

    @Test
    void anUntrustedRepositoryIsNeverFetchedSilently(@TempDir Path temp) {
        DependencyReport report = new DependencyReport(
                List.of(required("housegraph-widgets", "https://github.com/someone-else/housegraph-widgets")),
                List.of(), List.of());

        AutoInstallPlan plan = AutoInstallPlan.from(report, trusting(temp, REPOSITORY));

        assertFalse(plan.hasActions());
        assertEquals(1, plan.needsConfirmation().size());
    }

    @Test
    void aDisabledLibraryIsNeverReEnabledOnItsOwn(@TempDir Path temp) {
        // Disabling is an explicit user decision; a graph asking for it must not undo that.
        DependencyReport report = new DependencyReport(
                List.of(), List.of(required("housegraph-widgets", REPOSITORY)), List.of());

        AutoInstallPlan plan = AutoInstallPlan.from(report, trusting(temp, REPOSITORY));

        assertFalse(plan.hasActions());
        assertEquals(1, plan.needsConfirmation().size());
    }

    @Test
    void autoInstallOffProducesExactlyTheOldBehaviour(@TempDir Path temp) {
        PluginTrust trust = PluginTrust.loadFrom(temp.resolve("plugin-trust.json"));
        trust.trust(REPOSITORY); // trusted, but the master switch is off
        DependencyReport report = new DependencyReport(
                List.of(required("housegraph-widgets", REPOSITORY)),
                List.of(required("housegraph-other", REPOSITORY)),
                List.of());

        AutoInstallPlan plan = AutoInstallPlan.from(report, trust);

        assertFalse(plan.hasActions());
        assertEquals(2, plan.needsConfirmation().size(), "everything blocking still goes to the user");
    }

    @Test
    void aLibraryWithNoRecordedRepositoryCannotBeFetched(@TempDir Path temp) {
        // A v1 save, or one written before the full plugins row existed, names the library but not
        // where it came from. There is nothing to install from, trusted or not.
        DependencyReport report = new DependencyReport(
                List.of(required("housegraph-widgets", null)), List.of(), List.of());

        AutoInstallPlan plan = AutoInstallPlan.from(report, trusting(temp, REPOSITORY));

        assertFalse(plan.hasActions());
        assertEquals(1, plan.needsConfirmation().size());
    }

    @Test
    void aNonGitHubRepositoryIsRefusedEvenIfSomehowTrusted(@TempDir Path temp) {
        // Trust answers "did the user accept this?"; GitHubReleases.isAllowed answers "may we fetch
        // from it at all?". Both must pass.
        String elsewhere = "https://evil.example.com/housegraph-widgets";
        DependencyReport report = new DependencyReport(
                List.of(required("housegraph-widgets", elsewhere)), List.of(), List.of());

        AutoInstallPlan plan = AutoInstallPlan.from(report, trusting(temp, elsewhere));

        assertFalse(plan.hasActions());
        assertEquals(1, plan.needsConfirmation().size());
    }
}
