package io.github.jaymcole.housegraph.remote;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.plugin.PluginInstaller;

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
     * Installs — and now updates — the node libraries a repository's manifest declares, insofar as
     * the operator has permitted it.
     *
     * <h2>Why the manifest's {@code version} finally matters</h2>
     * This used to skip anything already in the catalog, so a library could be installed once and
     * then never moved again: bumping {@code version} in {@code housegraph.json} changed nothing, and
     * the only way to get a newer library onto a remote machine was to SSH in and run
     * {@code plugins update} by hand. The field was parsed and read by nobody.
     *
     * <p>Now an entry naming a version newer than what is installed triggers an update to the
     * repository's <em>latest</em> release. Latest rather than that exact version, deliberately:
     * {@code GitHubReleases} has no fetch-by-tag, and the manifest's number reads as "at least this",
     * which the newest release satisfies. {@code GraphDependencyCheck.isOlder} does the comparison,
     * and is lenient by design — a version scheme it cannot parse produces no update rather than a
     * wrong one.
     *
     * <p>Installing by library id, not by repository alone, because a monorepo release attaches a jar
     * per library and the id-less overload refuses those outright.
     *
     * <h2>What has not changed</h2>
     * Every skip is logged rather than silently tolerated, because "my graph came up with placeholder
     * nodes" is otherwise a mystery. Nothing here can widen the trust set: the manifest proposes,
     * {@link RemoteConfig#isTrustedForInstall} disposes, and a refused library simply means those
     * nodes load as placeholders — which the app already handles safely. The manifest remains the only
     * source of these declarations; save files are still not read here, for the reasons in
     * {@link RepoManifest}.
     *
     * <p>An updated jar reaches a running graph the same way a new one does: this runs only when a
     * repository has moved, and {@code Supervisor} then restarts every graph from it in a fresh JVM —
     * the only thing that reliably picks up a node-library change.
     */
    private void installDeclaredPlugins(GraphRepository repository) {
        Optional<RepoManifest> manifest = RepoManifest.read(repository.cloneDirectory());
        if (manifest.isEmpty() || manifest.get().plugins().isEmpty()) {
            return;
        }
        PluginCatalog catalog = PluginCatalog.load();
        for (RepoManifest.PluginEntry entry : manifest.get().plugins()) {
            PluginCatalog.Installed installed = catalog.byId(entry.id()).orElse(null);
            PluginAction action = decide(entry, installed);
            if (action == PluginAction.SKIP) {
                continue;
            }
            boolean update = action == PluginAction.UPDATE;
            if (entry.repository() == null) {
                log.warn("Manifest needs node library \"{}\" but records no repository for it", entry.id());
                continue;
            }
            if (!config.isTrustedForInstall(entry.repository())) {
                log.warn("Not {} \"{}\" from {} — add it to trustedPluginRepositories and set "
                                + "allowPluginInstall in remote.json if you want that. Its nodes will "
                                + "load as placeholders.",
                        update ? "updating" : "installing", entry.id(), entry.repository());
                continue;
            }
            try {
                if (update) {
                    log.info("Updating node library \"{}\" from {} (installed {}, manifest wants {})",
                            entry.id(), entry.repository(), installed.version(), entry.version());
                } else {
                    log.info("Installing node library \"{}\" from {}", entry.id(), entry.repository());
                }
                PluginInstaller.install(entry.repository(), entry.id(), catalog);
            } catch (IOException | RuntimeException e) {
                log.error("Could not install \"{}\" from {}", entry.id(), entry.repository(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** What a manifest entry calls for, given what is already installed. */
    enum PluginAction {
        INSTALL,
        UPDATE,
        SKIP
    }

    /**
     * The install/update/skip decision, split out from {@link #installDeclaredPlugins} so it can be
     * tested without a network, a clone, or a catalog on disk. Trust is <em>not</em> considered here —
     * that gate is applied separately by the caller, and keeping the two apart means a test of this
     * logic cannot accidentally pass by being refused.
     *
     * @param entry     what the manifest declares
     * @param installed the matching catalog entry, or null when the library isn't installed
     * @return what to do about it
     */
    static PluginAction decide(RepoManifest.PluginEntry entry, PluginCatalog.Installed installed) {
        if (installed == null) {
            return PluginAction.INSTALL;
        }
        return GraphDependencyCheck.isOlder(installed.version(), entry.version())
                ? PluginAction.UPDATE
                : PluginAction.SKIP;
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
