package io.github.jaymcole.housegraph.plugin;

import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck.DependencyReport;
import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck.RequiredPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoInstallPlanTest {

    private static final String REPOSITORY = "https://github.com/example/housegraph-widgets";

    /** Stands in for {@code RemoteConfig::isTrustedForInstall}. */
    private static final Predicate<String> TRUSTS_REPOSITORY = REPOSITORY::equals;
    private static final Predicate<String> TRUSTS_NOTHING = url -> false;

    private static RequiredPlugin required(String id, String repository) {
        return new RequiredPlugin(id, id, "1.2.0", repository);
    }

    @Test
    void aMissingLibraryFromATrustedRepositoryIsInstalled() {
        DependencyReport report = new DependencyReport(
                List.of(required("housegraph-widgets", REPOSITORY)), List.of(), List.of());

        AutoInstallPlan plan = AutoInstallPlan.from(report, TRUSTS_REPOSITORY);

        assertEquals(1, plan.actions().size());
        assertEquals(new AutoInstallPlan.Action("housegraph-widgets", REPOSITORY, AutoInstallPlan.Kind.INSTALL),
                plan.actions().get(0));
        assertTrue(plan.refused().isEmpty());
    }

    @Test
    void anOutOfDateLibraryFromATrustedRepositoryIsUpdated() {
        DependencyReport report = new DependencyReport(
                List.of(), List.of(), List.of(required("housegraph-widgets", REPOSITORY)));

        AutoInstallPlan plan = AutoInstallPlan.from(report, TRUSTS_REPOSITORY);

        assertEquals(AutoInstallPlan.Kind.UPDATE, plan.actions().get(0).kind());
        // Out of date is advisory: its nodes still resolve, so there is nothing wrong to report.
        assertTrue(plan.refused().isEmpty());
    }

    @Test
    void anUntrustedRepositoryIsNeverFetched() {
        DependencyReport report = new DependencyReport(
                List.of(required("housegraph-widgets", "https://github.com/someone-else/housegraph-widgets")),
                List.of(), List.of());

        AutoInstallPlan plan = AutoInstallPlan.from(report, TRUSTS_REPOSITORY);

        assertFalse(plan.hasActions());
        assertEquals(1, plan.refused().size());
    }

    @Test
    void aDisabledLibraryIsNeverReEnabledOnItsOwn() {
        // Disabling is an explicit decision recorded in the catalog; a graph asking must not undo it.
        DependencyReport report = new DependencyReport(
                List.of(), List.of(required("housegraph-widgets", REPOSITORY)), List.of());

        AutoInstallPlan plan = AutoInstallPlan.from(report, TRUSTS_REPOSITORY);

        assertFalse(plan.hasActions());
        assertEquals(1, plan.refused().size());
    }

    @Test
    void installsSwitchedOffProduceNoActionsAtAll() {
        DependencyReport report = new DependencyReport(
                List.of(required("housegraph-widgets", REPOSITORY)),
                List.of(required("housegraph-other", REPOSITORY)),
                List.of());

        AutoInstallPlan plan = AutoInstallPlan.from(report, TRUSTS_NOTHING);

        assertFalse(plan.hasActions());
        assertEquals(2, plan.refused().size(), "everything blocking is reported instead");
    }

    @Test
    void aLibraryWithNoRecordedRepositoryCannotBeFetched() {
        // A v1 save, or one written before the full plugins row existed, names the library but not
        // where it came from. There is nothing to install from, trusted or not.
        DependencyReport report = new DependencyReport(
                List.of(required("housegraph-widgets", null)), List.of(), List.of());

        AutoInstallPlan plan = AutoInstallPlan.from(report, url -> true);

        assertFalse(plan.hasActions());
        assertEquals(1, plan.refused().size());
    }

    @Test
    void aNonGitHubRepositoryIsRefusedEvenWhenThePredicateSaysYes() {
        // The predicate answers "did the operator permit this?"; GitHubReleases.isAllowed answers
        // "may we fetch from it at all?". Both must pass, so a permissive config cannot reach
        // outside GitHub.
        String elsewhere = "https://evil.example.com/housegraph-widgets";
        DependencyReport report = new DependencyReport(
                List.of(required("housegraph-widgets", elsewhere)), List.of(), List.of());

        AutoInstallPlan plan = AutoInstallPlan.from(report, url -> true);

        assertFalse(plan.hasActions());
        assertEquals(1, plan.refused().size());
    }
}
