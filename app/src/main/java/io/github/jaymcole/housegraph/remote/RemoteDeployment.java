package io.github.jaymcole.housegraph.remote;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.plugin.AutoInstallPlan;
import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.plugin.PluginInstaller;
import io.github.jaymcole.housegraph.ui.io.GraphFileIO;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns "the repository moved" into "these graphs should now be running".
 *
 * <p>Sits between {@link GraphRepository} (which knows git) and {@link Supervisor} (which knows
 * processes) so neither has to know about the other, and so the decision of <em>what</em> to deploy
 * is testable without either a network or a JVM to spawn.
 */
public final class RemoteDeployment {

    private static final Logger log = Log.get(RemoteDeployment.class);

    /**
     * What one repository wants running, after a sync.
     *
     * @param changed whether the commit differs from the last one deployed
     * @param sha     the commit now checked out
     * @param graphs  the graph files to run, already resolved and known to exist
     */
    public record Deployment(boolean changed, String sha, List<Path> graphs) {

        public Deployment {
            graphs = List.copyOf(graphs);
        }
    }

    private final RemoteConfig config;
    private final RemoteState state;

    public RemoteDeployment(RemoteConfig config, RemoteState state) {
        this.config = config;
        this.state = state;
    }

    /**
     * Checks one repository and, if it has moved, brings the mirror up to date and works out what
     * should run from it.
     *
     * <p>The remote is asked first and the mirror only touched when the answer differs, so the
     * steady state — nothing has been pushed — costs exactly one {@code ls-remote} and no disk
     * writes at all.
     *
     * @param repository the repository to check
     * @param force      sync and report changed even when the commit matches (a first start, where
     *                   the mirror may be missing even though the state file remembers the commit)
     * @return what should run, or empty when the repository could not be reached
     */
    public Optional<Deployment> refresh(GraphRepository repository, boolean force) {
        String key = repository.config().key();
        try {
            Optional<String> head = repository.remoteHead();
            if (head.isEmpty()) {
                log.error("{} has no branch \"{}\"", repository.config().url(), repository.config().branch());
                return Optional.empty();
            }

            boolean changed = force
                    || !repository.isCloned()
                    || state.lastSha(key).map(sha -> !sha.equals(head.get())).orElse(true);
            if (!changed) {
                return Optional.of(new Deployment(false, head.get(), graphsIn(repository)));
            }

            log.info("{} moved to {}", repository.config().url(), head.get().substring(0, 7));
            String synced = repository.sync();
            state.record(key, synced);
            state.save();
            installDeclaredPlugins(repository);
            return Optional.of(new Deployment(true, synced, graphsIn(repository)));
        } catch (GitCommand.GitException e) {
            // A repository that can't be reached must not take down the graphs already running from
            // it, nor stop the daemon checking the others. Report and move on; the next poll retries.
            log.error("Could not sync {}: {}", repository.config().url(), e.getMessage());
            return Optional.empty();
        }
    }

    private List<Path> graphsIn(GraphRepository repository) {
        return RepoManifest.read(repository.cloneDirectory())
                .map(manifest -> manifest.resolveGraphs(repository.cloneDirectory()))
                .orElseGet(() -> {
                    log.error("{} has no {} at its root, so nothing will run from it",
                            repository.config().url(), RepoManifest.FILE_NAME);
                    return List.of();
                });
    }

    /**
     * Installs and updates the node libraries a repository needs, insofar as the operator has
     * permitted it.
     *
     * <h4>Save files are read here — a deliberate reversal</h4>
     * {@link RepoManifest} used to argue that the daemon must never take dependencies from save
     * files, on the grounds that such a table describes what a graph was built against on someone
     * else's machine. That reasoning does not survive contact with how a graph repository is actually
     * used: the save files in it are commits in a repository the operator <b>named by hand</b> in
     * {@code remote.json}, sitting beside the very manifest they were being contrasted with. Anyone
     * who can commit a save file there can already commit a manifest, or a graph that does anything
     * at all. Reading both widens nothing meaningful, and it is what lets a fresh server come up
     * with no per-library configuration.
     *
     * <p>The manifest still earns its keep, and still comes first: it is the only place a
     * <em>version floor</em> can be declared, which is what makes updates possible, and its entries
     * take precedence per {@link GraphDependencyCheck#classify}'s first-wins rule. A save file's
     * recorded version is whatever the authoring machine happened to have; the manifest's is a
     * statement of intent someone wrote down.
     *
     * <h4>Versions mean "at least this"</h4>
     * A requirement naming a version newer than what is installed triggers an update to the
     * repository's <em>latest</em> release — latest rather than that exact version, because
     * {@code GitHubReleases} has no fetch-by-tag and the newest release satisfies "at least".
     * {@code GraphDependencyCheck.isOlder} is lenient by design: a version scheme it cannot parse
     * produces no update rather than a wrong one, since a false positive here downloads a jar and
     * restarts a graph for nothing.
     *
     * <h4>What has not changed</h4>
     * Nothing here can widen the trust set: the repository proposes,
     * {@link RemoteConfig#isTrustedForInstall} disposes, and a refused library simply means those
     * nodes load as placeholders — which the app already handles safely. Every refusal is logged
     * rather than silently tolerated, because "my graph came up with placeholder nodes" is otherwise
     * a mystery. A <b>disabled</b> library is never re-enabled by any of this; see
     * {@link AutoInstallPlan}.
     *
     * <p>An updated jar reaches a running graph the same way a new one does: this runs only when a
     * repository has moved, and {@code Supervisor} then restarts every graph from it in a fresh JVM —
     * the only thing that reliably picks up a node-library change.
     */
    private void installDeclaredPlugins(GraphRepository repository) {
        PluginCatalog catalog = PluginCatalog.load();
        List<GraphDependencyCheck.RequiredPlugin> required = requirementsOf(repository);
        if (required.isEmpty()) {
            return;
        }

        AutoInstallPlan plan = AutoInstallPlan.from(
                GraphDependencyCheck.classify(required, catalog), config::isTrustedForInstall);

        for (GraphDependencyCheck.RequiredPlugin refused : plan.refused()) {
            log.warn("Not installing \"{}\"{} — {}", refused.id(),
                    refused.repository() == null ? "" : " from " + refused.repository(),
                    explainRefusal(refused, catalog));
        }
        if (!plan.hasActions()) {
            return;
        }
        PluginInstaller.AutoInstallOutcome outcome = PluginInstaller.apply(plan, catalog);
        if (!outcome.failed().isEmpty()) {
            log.error("Node libraries that could not be installed: {}", String.join(", ", outcome.failed()));
        }
    }

    /**
     * What a repository needs, manifest first so its declarations win, then every graph it deploys.
     *
     * <p>Reading save files with {@code GraphFileIO.readRoot} is precedent, not a new coupling —
     * {@code CheckCommand} already does exactly this from a headless command, and {@code readRoot}
     * is a JSON parse that builds nothing and loads no class.
     */
    private List<GraphDependencyCheck.RequiredPlugin> requirementsOf(GraphRepository repository) {
        List<GraphDependencyCheck.RequiredPlugin> required = new ArrayList<>();
        RepoManifest.read(repository.cloneDirectory()).ifPresent(manifest -> {
            for (RepoManifest.PluginEntry entry : manifest.plugins()) {
                if (entry.repository() == null) {
                    log.warn("Manifest needs node library \"{}\" but records no repository for it", entry.id());
                }
                required.add(new GraphDependencyCheck.RequiredPlugin(
                        entry.id(), entry.id(), entry.version(), entry.repository()));
            }
        });

        for (Path graph : graphsIn(repository)) {
            try {
                required.addAll(GraphDependencyCheck.requiredBy(GraphFileIO.readRoot(graph.toFile())));
            } catch (IOException | RuntimeException e) {
                // One unreadable graph must not stop the others' libraries being installed. The graph
                // itself will fail to start and say so through the supervisor.
                log.error("Could not read {} to see which node libraries it needs: {}", graph, e.toString());
            }
        }
        return required;
    }

    /** Why a requirement was refused, so the log line names the fix rather than just the symptom. */
    private String explainRefusal(GraphDependencyCheck.RequiredPlugin refused, PluginCatalog catalog) {
        if (catalog.byId(refused.id()).map(installed -> !installed.enabled()).orElse(false)) {
            return "it is installed but disabled. Re-enable it with the library window or by editing "
                    + "config/plugins.json; nothing here re-enables a library on its own.";
        }
        if (refused.repository() == null || refused.repository().isBlank()) {
            return "no repository is recorded for it. Add a plugins[] entry to "
                    + RepoManifest.FILE_NAME + ", or re-save the graph on a machine that has it installed.";
        }
        if (!config.allowPluginInstall()) {
            return "allowPluginInstall is off in remote.json.";
        }
        return "it is not on trustedPluginRepositories in remote.json.";
    }

    /** The repositories this deployment tracks, one per configured entry. */
    public List<GraphRepository> repositories() {
        List<GraphRepository> repositories = new ArrayList<>();
        for (RemoteConfig.Repository entry : config.repositories()) {
            repositories.add(new GraphRepository(entry));
        }
        return repositories;
    }
}
