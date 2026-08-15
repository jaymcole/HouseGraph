package io.github.jaymcole.housegraph.plugin;

import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck.DependencyReport;
import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck.RequiredPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides what may be installed or updated without asking, given what a save file needs
 * ({@link DependencyReport}) and what the user has already trusted ({@link PluginTrust}).
 *
 * <h2>Pure, so it can be tested</h2>
 * Every judgment about downloading code lives here rather than in {@code App}, where a JavaFX
 * dependency would put it beyond this repository's reach — there is no infrastructure for testing
 * windows, so anything worth testing must be headless. This is one pass over a parsed report: no
 * I/O, no network, no class loading, the same shape as {@link GraphDependencyCheck#inspect} that
 * produced its input. The caller performs the resulting {@link Action}s; it does not re-decide them.
 *
 * <h2>What is deliberately not auto-acted on</h2>
 * A <b>disabled</b> library is never in {@link #actions()}. Disabling one is an explicit choice the
 * user made in the library window, and silently re-enabling it because a graph asked would override
 * that choice using the graph as the excuse — exactly the "save file as untrusted input" problem this
 * whole design exists to contain. It goes to {@link #needsConfirmation()} like anything else that
 * needs a person.
 *
 * <p>An <b>untrusted</b> repository is likewise never auto-installed, however installable it looks.
 * {@link RequiredPlugin#isInstallable()} only says a repository URL was recorded and points at
 * GitHub; it says nothing about whether the user ever agreed to run code from there.
 */
public record AutoInstallPlan(List<Action> actions, List<RequiredPlugin> needsConfirmation) {

    /** Whether an action installs a library that is absent, or replaces one that is behind. */
    public enum Kind {
        INSTALL,
        UPDATE
    }

    /**
     * One library to fetch, already checked against the trust store.
     *
     * @param pluginId   which library to take from the release — a monorepo attaches several
     * @param repository where to fetch it from
     * @param kind       whether this fills a gap or moves an existing install forward
     */
    public record Action(String pluginId, String repository, Kind kind) {
    }

    public AutoInstallPlan {
        actions = List.copyOf(actions);
        needsConfirmation = List.copyOf(needsConfirmation);
    }

    /** Whether there is anything to fetch. */
    public boolean hasActions() {
        return !actions.isEmpty();
    }

    /**
     * Splits a dependency report into what may be fetched silently and what still needs a person.
     *
     * <p>With auto-install switched off, {@link PluginTrust#isTrustedForInstall} is false for
     * everything, so this returns no actions and routes the whole report to
     * {@link #needsConfirmation()} — byte-for-byte the behaviour before auto-install existed. That is
     * the property worth preserving: the feature being off must not be a different code path, just an
     * empty answer from the same one.
     *
     * @param report what the save file says it needs
     * @param trust  the repositories the user has accepted
     * @return the split
     */
    public static AutoInstallPlan from(DependencyReport report, PluginTrust trust) {
        List<Action> actions = new ArrayList<>();
        List<RequiredPlugin> needsConfirmation = new ArrayList<>();

        for (RequiredPlugin required : report.missing()) {
            if (canFetch(required, trust)) {
                actions.add(new Action(required.id(), required.repository(), Kind.INSTALL));
            } else {
                needsConfirmation.add(required);
            }
        }

        // Installed but switched off. Never auto-acted on; see the class Javadoc.
        needsConfirmation.addAll(report.disabled());

        // Behind what the graph was saved against. There is no fetch-by-tag and none is wanted: the
        // recorded version says "at least this", so moving to the latest release satisfies it.
        for (RequiredPlugin required : report.olderThanSaved()) {
            if (canFetch(required, trust)) {
                actions.add(new Action(required.id(), required.repository(), Kind.UPDATE));
            }
            // An out-of-date library is not added to needsConfirmation: its nodes still resolve, so
            // there is nothing to interrupt the user about. GraphDependencyCheck already treats this
            // as advisory rather than blocking, and that stays true.
        }

        return new AutoInstallPlan(actions, needsConfirmation);
    }

    private static boolean canFetch(RequiredPlugin required, PluginTrust trust) {
        return required.isInstallable() && trust.isTrustedForInstall(required.repository());
    }
}
