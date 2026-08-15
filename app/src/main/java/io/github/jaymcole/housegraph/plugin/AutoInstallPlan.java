package io.github.jaymcole.housegraph.plugin;

import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck.DependencyReport;
import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck.RequiredPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Decides what may be installed or updated without anyone to ask, given what a graph needs
 * ({@link DependencyReport}) and a predicate saying which repositories may be fetched from.
 *
 * <h2>Only the daemon uses this</h2>
 * There is no auto-install in the desktop app, deliberately: a save file there may have arrived from
 * anywhere, so it can propose a code download but must never cause one. Unattended, the trust basis
 * is different in kind — the operator hand-wrote the graph repository's URL in {@code remote.json},
 * and a save file inside that repository is a committed artifact of a repository they named. The
 * predicate passed in is {@code RemoteConfig::isTrustedForInstall}, which carries that decision.
 *
 * <h2>Pure, so it can be tested</h2>
 * One pass over an already-parsed report: no I/O, no network, no class loading, the same shape as
 * {@link GraphDependencyCheck#inspect} that produced its input. {@link PluginInstaller#apply}
 * performs the resulting {@link Action}s; it does not re-decide them.
 *
 * <h2>What is deliberately not auto-acted on</h2>
 * A <b>disabled</b> library is never in {@link #actions()}. Disabling one is an explicit choice
 * recorded in the catalog, and re-enabling it because a graph asked would override that choice using
 * the graph as the excuse. That matters more unattended, not less: there is nobody to notice.
 *
 * <p>An <b>untrusted</b> repository is likewise never fetched, however installable it looks.
 * {@link RequiredPlugin#isInstallable()} only says a repository URL was recorded and points at
 * GitHub; the predicate is what says the operator permitted it. Both must pass.
 */
public record AutoInstallPlan(List<Action> actions, List<RequiredPlugin> refused) {

    /** Whether an action installs a library that is absent, or replaces one that is behind. */
    public enum Kind {
        INSTALL,
        UPDATE
    }

    /**
     * One library to fetch, already checked against the trust predicate.
     *
     * @param pluginId   which library to take from the release — a monorepo attaches several
     * @param repository where to fetch it from
     * @param kind       whether this fills a gap or moves an existing install forward
     */
    public record Action(String pluginId, String repository, Kind kind) {
    }

    public AutoInstallPlan {
        actions = List.copyOf(actions);
        refused = List.copyOf(refused);
    }

    /** Whether there is anything to fetch. */
    public boolean hasActions() {
        return !actions.isEmpty();
    }

    /**
     * Splits a dependency report into what may be fetched and what may not.
     *
     * <p>With installs switched off, the predicate is false for everything, so this returns no
     * actions and every blocking entry lands in {@link #refused()} — which the caller logs. The
     * feature being off is the same code path with an empty answer, not a second branch that can rot.
     *
     * @param report          what the graphs say they need
     * @param mayInstallFrom  whether a given repository URL may be fetched from
     * @return the split
     */
    public static AutoInstallPlan from(DependencyReport report, Predicate<String> mayInstallFrom) {
        List<Action> actions = new ArrayList<>();
        List<RequiredPlugin> refused = new ArrayList<>();

        for (RequiredPlugin required : report.missing()) {
            if (canFetch(required, mayInstallFrom)) {
                actions.add(new Action(required.id(), required.repository(), Kind.INSTALL));
            } else {
                refused.add(required);
            }
        }

        // Installed but switched off. Never auto-acted on; see the class Javadoc.
        refused.addAll(report.disabled());

        // Behind what the graph was saved against. There is no fetch-by-tag and none is wanted: a
        // recorded version reads as "at least this", so the latest release satisfies it.
        for (RequiredPlugin required : report.olderThanSaved()) {
            if (canFetch(required, mayInstallFrom)) {
                actions.add(new Action(required.id(), required.repository(), Kind.UPDATE));
            }
            // An out-of-date library is not refused-and-reported: its nodes still resolve, so there
            // is nothing wrong to announce. GraphDependencyCheck already treats this as advisory
            // rather than blocking, and that stays true.
        }

        return new AutoInstallPlan(actions, refused);
    }

    private static boolean canFetch(RequiredPlugin required, Predicate<String> mayInstallFrom) {
        return required.isInstallable() && mayInstallFrom.test(required.repository());
    }
}
