package io.github.jaymcole.housegraph.remote;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.storage.AppDirectories;
import io.github.jaymcole.housegraph.storage.SecretsStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;

/**
 * One tracked graph repository: its local mirror, and the two operations the daemon needs — ask the
 * remote where it is, and make the mirror match.
 *
 * <h2>A mirror, not a working copy</h2>
 * Updating is {@code fetch} then {@code reset --hard} then {@code clean -fd}, never {@code pull}.
 * A pull can conflict, and a conflict on an unattended machine is a silent hang with no one to
 * resolve it — the daemon would sit there believing it was up to date. Resetting cannot fail that
 * way. The cost is that anything edited by hand inside the clone is discarded, which is the correct
 * trade for a directory whose entire purpose is to reflect what was pushed.
 *
 * <h2>Polling is cheap on purpose</h2>
 * {@code git ls-remote} is a single short-lived connection speaking the git protocol. It is <b>not</b>
 * {@code api.github.com}, so the 60-requests-per-hour budget that forces {@code GitHubReleases} to
 * only ever check on user action does not apply. That is what makes a once-a-minute poll reasonable
 * where an API poll would not be.
 */
public final class GraphRepository {

    private static final Logger log = Log.get(GraphRepository.class);

    private final RemoteConfig.Repository config;
    private final Path clone;

    public GraphRepository(RemoteConfig.Repository config) {
        this(config, AppDirectories.get().remoteRepo(config.key()));
    }

    /** With an explicit clone directory, so a test can work in a temp dir. */
    GraphRepository(RemoteConfig.Repository config, Path clone) {
        this.config = config;
        this.clone = clone;
    }

    public RemoteConfig.Repository config() {
        return config;
    }

    /** Where this repository's mirror lives on disk. */
    public Path cloneDirectory() {
        return clone;
    }

    /** Whether the mirror has been created yet. */
    public boolean isCloned() {
        return Files.isDirectory(clone.resolve(".git"));
    }

    /**
     * The commit the remote's tracked branch currently points at, without touching the mirror.
     *
     * <p>This is the poll. It is the only network call made on a timer.
     *
     * @return the commit id, or empty when the branch doesn't exist on the remote
     */
    public Optional<String> remoteHead() {
        GitCommand.Result result = git()
                .withTimeoutSeconds(60)
                .run("ls-remote", config.url(), "refs/heads/" + config.branch())
                .orThrow("Checking " + config.url());
        String line = result.firstLine();
        if (line.isBlank()) {
            return Optional.empty();
        }
        // "<sha>\trefs/heads/<branch>"
        String sha = line.split("\\s+")[0].trim();
        return sha.isBlank() ? Optional.empty() : Optional.of(sha);
    }

    /** The commit the local mirror is on, or empty when it hasn't been cloned. */
    public Optional<String> localHead() {
        if (!isCloned()) {
            return Optional.empty();
        }
        GitCommand.Result result = git().run("rev-parse", "HEAD");
        return result.succeeded() ? Optional.of(result.firstLine()) : Optional.empty();
    }

    /**
     * Brings the mirror to the remote's current tip, cloning it first if necessary.
     *
     * @return the commit now checked out
     */
    public String sync() {
        if (!isCloned()) {
            cloneFresh();
        } else {
            update();
        }
        return localHead().orElseThrow(
                () -> new GitCommand.GitException("Synced " + config.url() + " but it has no HEAD"));
    }

    private void cloneFresh() {
        try {
            Files.createDirectories(clone.getParent());
        } catch (IOException e) {
            throw new GitCommand.GitException("Could not create " + clone.getParent(), e);
        }
        log.info("Cloning {} ({}) into {}", config.url(), config.branch(), clone);
        // --depth 1: the daemon only ever runs the tip, and a shallow mirror keeps a repository with
        // a long history from costing minutes on a first start.
        GitCommand.in(clone.getParent())
                .withEnvironment(credentialEnvironment())
                .run("clone", "--depth", "1", "--branch", config.branch(),
                        config.url(), clone.getFileName().toString())
                .orThrow("Cloning " + config.url());
    }

    private void update() {
        GitCommand git = git().withEnvironment(credentialEnvironment());
        git.run("fetch", "--depth", "1", "origin", config.branch()).orThrow("Fetching " + config.url());
        git.run("reset", "--hard", "FETCH_HEAD").orThrow("Updating " + clone);
        // Files deleted upstream survive a reset if they are untracked here — a graph removed from
        // the repository has to stop running, so the mirror is cleaned rather than merely reset.
        git.run("clean", "-fd").orThrow("Cleaning " + clone);
    }

    private GitCommand git() {
        return GitCommand.in(isCloned() ? clone : null);
    }

    /**
     * Environment for git when this repository needs an HTTPS token.
     *
     * <p>The token goes into {@code GIT_ASKPASS} — a throwaway script that echoes it — rather than
     * into the remote URL, because {@code argv} is readable by every process on the machine while a
     * child's environment is not. For an SSH URL this returns nothing at all: the key is the user's,
     * handled by their agent, and HouseGraph never sees a credential.
     */
    private Map<String, String> credentialEnvironment() {
        if (config.tokenSecret() == null) {
            return Map.of();
        }
        String token = SecretsStore.open().get(config.tokenSecret());
        if (token == null || token.isBlank()) {
            log.warn("Repository {} names secret \"{}\" but it is not set; trying without it",
                    config.url(), config.tokenSecret());
            return Map.of();
        }
        try {
            Path askpass = Files.createTempFile("housegraph-askpass", ".sh",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            askpass.toFile().deleteOnExit();
            Files.writeString(askpass, "#!/bin/sh\nexec printf '%s' \"$HOUSEGRAPH_GIT_TOKEN\"\n",
                    StandardCharsets.UTF_8);
            return Map.of("GIT_ASKPASS", askpass.toString(),
                    "HOUSEGRAPH_GIT_TOKEN", token,
                    // Without this, a wrong token makes git block on an interactive prompt that
                    // nobody will ever answer — the daemon would hang instead of failing.
                    "GIT_TERMINAL_PROMPT", "0");
        } catch (IOException | UnsupportedOperationException e) {
            log.error("Could not prepare git credentials for {}", config.url(), e);
            return Map.of();
        }
    }
}
