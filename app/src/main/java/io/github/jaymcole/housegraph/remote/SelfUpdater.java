package io.github.jaymcole.housegraph.remote;

import io.github.jaymcole.housegraph.AppVersion;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.plugin.GitHubReleases;
import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Keeps HouseGraph itself up to date on a machine nobody logs into.
 *
 * <h2>Swap the jar, then exit</h2>
 * A running JVM cannot become a different build of itself. So an update is two halves: replace the
 * jar on disk, and let the thing that started this process start it again. The daemon exits with
 * {@link ExitCodes#RESTART_REQUESTED} and its supervisor — the LaunchAgent's {@code KeepAlive},
 * systemd's {@code Restart} — does the rest. That is the same contract a supervised graph already
 * uses to ask for a fresh JVM, one layer up.
 *
 * <p>Replacing the file underneath a running JVM is safe on Unix precisely because the swap is a
 * rename: the running process keeps the inode it already opened, so the daemon and its graph children
 * carry on with the old jar until each is next started. It is not safe on Windows, which will not let
 * an open jar be replaced at all, so {@link #canApply()} refuses there rather than downloading sixty
 * megabytes that cannot be installed.
 *
 * <h2>What it refuses to do</h2>
 * Every check that stands between "a newer release exists" and "replace the jar" is there because the
 * failure it prevents is unattended and therefore silent:
 *
 * <ul>
 *   <li><b>A development build never updates.</b> No manifest version means no honest comparison,
 *       and a working tree is not behind anything.</li>
 *   <li><b>Only this machine's jar.</b> {@link UpdatePlatform} matches the release asset on OS
 *       <em>and</em> architecture; a release with no jar for this machine is left alone.</li>
 *   <li><b>The new jar has to start.</b> It is run as {@code java -jar <staged> --version} before it
 *       is installed, which catches a truncated download, a jar built for a newer Java, and a
 *       version that is not the one the release advertised.</li>
 *   <li><b>The same release is never applied twice.</b> If the previous swap did not take — the
 *       supervisor runs a jar from somewhere else, say — the version the updater installed is
 *       remembered, and finding it again stops the loop instead of feeding it.</li>
 * </ul>
 *
 * <p>The jar it replaces is the one this process was loaded from, and the one it replaced is kept
 * next to it as {@code <jar>.previous} — the only rollback there is on a machine with no operator
 * at the keyboard.
 */
public final class SelfUpdater {

    private static final Logger log = Log.get(SelfUpdater.class);

    /** The suffix of the kept-aside jar, so a bad release can be put back by hand. */
    static final String PREVIOUS_SUFFIX = ".previous";

    /** Where the download lands: beside the target, so installing it is a same-filesystem rename. */
    static final String STAGED_SUFFIX = ".new";

    /** How long the staged jar gets to print its version before it is judged unable to start. */
    private static final long VERIFY_TIMEOUT_SECONDS = 120;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** What a check concluded, and why. */
    public enum Action {
        /** A newer release exists, with a jar for this machine. */
        UPDATE,
        /** This machine is on the latest release. */
        UP_TO_DATE,
        /** The latest release is one this machine already installed; the swap did not take. */
        ALREADY_APPLIED,
        /** The release carries no jar for this platform. */
        NO_ASSET,
        /** Running from exploded classes, or from a jar with no version in its manifest. */
        UNKNOWN_VERSION,
        /** This machine cannot apply an update at all — no jar, no matching platform, Windows. */
        UNSUPPORTED,
        /** The lookup itself failed: offline, rate-limited, no release published. */
        FAILED
    }

    /**
     * The outcome of a check: what to do, said in one line a human can act on.
     *
     * @param action  what the updater concluded
     * @param message the reason, phrased for an operator reading a log or a terminal
     * @param version the release version this is about, or null when there is none
     * @param asset   the jar to install, set only when {@code action} is {@link Action#UPDATE}
     */
    public record Decision(Action action, String message, String version, GitHubReleases.Asset asset) {

        static Decision of(Action action, String message) {
            return new Decision(action, message, null, null);
        }

        static Decision of(Action action, String message, String version) {
            return new Decision(action, message, version, null);
        }

        /** Whether this decision says there is something to install. */
        public boolean isUpdate() {
            return action == Action.UPDATE && asset != null;
        }
    }

    /** Asks GitHub for a repository's latest release. Injected so a check can be tested offline. */
    @FunctionalInterface
    public interface Lookup {
        /**
         * @param repositoryUrl the repository to ask about
         * @param knownEtag     the ETag from the last lookup, or null
         * @return the release, or empty when nothing has changed since {@code knownEtag}
         * @throws IOException          if the request fails
         * @throws InterruptedException if the calling thread is interrupted
         */
        Optional<GitHubReleases.Release> latest(String repositoryUrl, String knownEtag)
                throws IOException, InterruptedException;
    }

    /** Fetches a release asset to a path. Injected so the install can be tested without GitHub. */
    @FunctionalInterface
    public interface Downloader {
        void download(GitHubReleases.Asset asset, Path target) throws IOException, InterruptedException;
    }

    /** Runs a downloaded jar and reports the version it claims. Empty when it would not start. */
    @FunctionalInterface
    public interface Verifier {
        Optional<String> reportedVersion(Path jar);
    }

    private final RemoteConfig.SelfUpdate config;
    private final RemoteState state;
    private final Path jar;
    private final String currentVersion;
    private final UpdatePlatform platform;
    private final Lookup lookup;
    private final Downloader downloader;
    private final Verifier verifier;

    /**
     * The real thing: this process's own jar, this machine's platform, GitHub at the other end.
     *
     * @param config the {@code selfUpdate} block from {@code remote.json}
     * @param state  the state file to remember the ETag and the last applied version in
     */
    public SelfUpdater(RemoteConfig.SelfUpdate config, RemoteState state) {
        this(config, state, GraphProcess.runningJar(), AppVersion.current().orElse(null),
                UpdatePlatform.current().orElse(null),
                GitHubReleases::latest, SelfUpdater::fetch, SelfUpdater::runVersionCheck);
    }

    /** With every outside edge injected, so the decisions and the swap are testable. */
    SelfUpdater(RemoteConfig.SelfUpdate config,
                RemoteState state,
                Path jar,
                String currentVersion,
                UpdatePlatform platform,
                Lookup lookup,
                Downloader downloader,
                Verifier verifier) {
        this.config = config;
        this.state = state;
        this.jar = jar;
        this.currentVersion = currentVersion;
        this.platform = platform;
        this.lookup = lookup;
        this.downloader = downloader;
        this.verifier = verifier;
    }

    /** The jar that would be replaced, for {@code doctor} and for the terminal. */
    public Optional<Path> targetJar() {
        return Optional.ofNullable(jar);
    }

    /**
     * Whether this machine could install an update if one existed.
     *
     * <h4>Checked before the network, not after</h4>
     * All three failures here are permanent for the life of the process, and none of them is worth
     * finding out about after a sixty-megabyte download.
     *
     * @return empty when an update could be applied, or the reason it could not
     */
    public Optional<String> canApply() {
        if (jar == null) {
            return Optional.of("not running from a jar, so there is nothing to replace — "
                    + "self-update only applies to a machine running the shaded jar");
        }
        if (platform == null) {
            return Optional.of("no published jar matches this machine (os.name="
                    + System.getProperty("os.name", "?") + ", os.arch=" + System.getProperty("os.arch", "?")
                    + "); build from source to update it");
        }
        if (platform == UpdatePlatform.WINDOWS) {
            return Optional.of("Windows will not let a running jar be replaced; "
                    + "update it with the daemon stopped");
        }
        return Optional.empty();
    }

    /**
     * Asks GitHub what the latest release is and decides what to do about it.
     *
     * <p>Records the ETag whenever the answer needs no action, so the next check is free. An answer
     * that <em>does</em> need action is deliberately not recorded until it has been applied — a
     * download that fails must be retried, not remembered as handled.
     *
     * @return what this machine should do
     */
    public Decision check() {
        Optional<String> blocked = canApply();
        if (blocked.isPresent()) {
            return Decision.of(Action.UNSUPPORTED, blocked.get());
        }
        Optional<GitHubReleases.Release> release;
        try {
            release = lookup.latest(config.repository(), state.releaseEtag().orElse(null));
        } catch (GitHubReleases.LookupException e) {
            return Decision.of(Action.FAILED, e.getMessage());
        } catch (IOException e) {
            return Decision.of(Action.FAILED, "Could not reach GitHub: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Decision.of(Action.FAILED, "Interrupted while checking for an update");
        }
        if (release.isEmpty()) {
            // 304: the latest release is the one this machine has already weighed up, whatever it
            // concluded then. Cheaper than the answer, and it costs nothing against the API budget.
            return Decision.of(Action.UP_TO_DATE, "Nothing new since the last check", currentVersion);
        }

        Decision decision = decide(currentVersion, state.appliedVersion().orElse(null),
                release.get(), platform);
        if (!decision.isUpdate()) {
            state.recordReleaseEtag(release.get().etag());
            state.save();
        }
        return decision;
    }

    /**
     * What to do about a release, given what this machine is running. Pure, so every branch is
     * testable without a network or a filesystem.
     *
     * @param currentVersion the running build's version, or null for a development build
     * @param appliedVersion the version this machine last installed for itself, or null
     * @param release        the latest release
     * @param platform       the platform whose asset this machine can run
     * @return the decision
     */
    static Decision decide(String currentVersion,
                           String appliedVersion,
                           GitHubReleases.Release release,
                           UpdatePlatform platform) {
        if (currentVersion == null || currentVersion.isBlank()) {
            return Decision.of(Action.UNKNOWN_VERSION,
                    "This build has no version in its manifest, so it cannot be compared with "
                            + release.tagName() + " — development builds are never updated",
                    release.version());
        }
        if (!GraphDependencyCheck.isOlder(currentVersion, release.version())) {
            return Decision.of(Action.UP_TO_DATE,
                    "Running " + currentVersion + "; the latest release is " + release.tagName(),
                    release.version());
        }
        if (release.version().equals(appliedVersion)) {
            return Decision.of(Action.ALREADY_APPLIED,
                    "Installed " + release.tagName() + " already, but this process is still running "
                            + currentVersion + ". The supervisor is starting a different jar from the "
                            + "one being replaced; not installing it again",
                    release.version());
        }
        Optional<GitHubReleases.Asset> asset = platform.assetIn(release.assets());
        if (asset.isEmpty()) {
            return Decision.of(Action.NO_ASSET,
                    release.tagName() + " has no " + platform.assetToken() + " jar attached",
                    release.version());
        }
        return new Decision(Action.UPDATE,
                "Update available: " + currentVersion + " → " + release.version(),
                release.version(), asset.get());
    }

    /**
     * Downloads, verifies and installs the jar a decision names.
     *
     * <p>The old jar is copied aside before the new one is moved into place, and the move is atomic,
     * so a failure at any point leaves a machine that still has a jar it can run.
     *
     * @param decision an {@link Action#UPDATE} decision from {@link #check()}
     * @return true when the new jar is installed and the process should be restarted onto it
     */
    public boolean apply(Decision decision) {
        if (!decision.isUpdate() || jar == null) {
            return false;
        }
        Path staged = jar.resolveSibling(jar.getFileName() + STAGED_SUFFIX);
        try {
            log.info("Downloading {} ({} MiB)", decision.asset().name(),
                    Math.max(1, decision.asset().sizeBytes() / (1024 * 1024)));
            downloader.download(decision.asset(), staged);

            Optional<String> reported = verifier.reportedVersion(staged);
            if (reported.isEmpty()) {
                log.error("The downloaded {} would not start; keeping {}",
                        decision.asset().name(), currentVersion);
                return false;
            }
            // Contains, not equals: a JVM given JAVA_TOOL_OPTIONS prints its own line before the
            // program says anything, and that is not a reason to refuse a good jar.
            if (!reported.get().contains(decision.version())) {
                log.error("The downloaded jar reports \"{}\" but the release said {}; not installing it",
                        reported.get(), decision.version());
                return false;
            }

            install(staged, jar);
            state.recordAppliedVersion(decision.version());
            // Forget the ETag rather than keep it. The next check then gets a full answer instead of
            // a 304, which is what lets it notice — and say — that the swap did not take, in the case
            // where the supervisor runs a jar from somewhere other than the one just replaced.
            state.recordReleaseEtag(null);
            state.save();
            log.info("Installed HouseGraph {} into {} (previous build kept as {})",
                    decision.version(), jar.getFileName(), jar.getFileName() + PREVIOUS_SUFFIX);
            return true;
        } catch (AccessDeniedException e) {
            log.error("Not allowed to replace {} — check who owns it, or update it by hand", jar, e);
            return false;
        } catch (IOException e) {
            log.error("Could not install the update", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while downloading an update");
            return false;
        } catch (RuntimeException e) {
            log.error("Could not install the update", e);
            return false;
        } finally {
            deleteQuietly(staged);
        }
    }

    /**
     * Puts {@code staged} in place of {@code target}, keeping what was there as {@code .previous}.
     *
     * <p>Copy the old one aside first, then move the new one over it: at no point between the two is
     * there no runnable jar at {@code target}, which matters because a supervisor may be about to
     * start one.
     *
     * @param staged the verified new jar, beside {@code target}
     * @param target the jar to replace
     * @throws IOException if either step fails
     */
    static void install(Path staged, Path target) throws IOException {
        Path previous = target.resolveSibling(target.getFileName() + PREVIOUS_SUFFIX);
        Files.copy(target, previous, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Same directory, so this should not happen; some network filesystems disagree.
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The default {@link Downloader}: {@code PluginInstaller}'s rules, without its progress UI. */
    private static void fetch(GitHubReleases.Asset asset, Path target)
            throws IOException, InterruptedException {
        if (!GitHubReleases.isAllowed(asset.downloadUrl())) {
            throw new IOException("Refusing to download from " + asset.downloadUrl()
                    + " — only GitHub is allowed.");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(asset.downloadUrl()))
                .header("Accept", "application/octet-stream")
                .timeout(Duration.ofMinutes(15))
                .GET()
                .build();
        // Truncating, explicitly: the default options for ofFile are CREATE and WRITE, which would
        // leave the tail of a longer half-finished download from a previous attempt in place and
        // hand the verifier a jar that is neither build.
        HttpResponse<Path> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(target,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
        if (response.statusCode() != 200) {
            throw new IOException("Download failed with HTTP " + response.statusCode()
                    + " for " + asset.downloadUrl());
        }
    }

    /**
     * The default {@link Verifier}: start the staged jar and read back {@code housegraph <version>}.
     *
     * <h4>Why run it at all</h4>
     * {@code --version} is the cheapest command that proves the whole chain — the zip is intact, this
     * JVM can load it, and the build inside is the one the release advertised. None of that is
     * visible from the file itself, and all of it is a reason not to overwrite a jar that currently
     * works. It says nothing about JavaFX's natives, which only a window would exercise; the
     * platform match is what covers those.
     *
     * @param staged the downloaded jar
     * @return what it printed, or empty when it failed to run
     */
    private static Optional<String> runVersionCheck(Path staged) {
        String java = ProcessHandle.current().info().command().orElse("java");
        Process process = null;
        try {
            process = new ProcessBuilder(java, "-jar", staged.toString(), "--version")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!process.waitFor(VERIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.error("The downloaded jar did not answer --version within {}s",
                        VERIFY_TIMEOUT_SECONDS);
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                log.error("The downloaded jar exited with {} on --version: {}",
                        process.exitValue(), output);
                return Optional.empty();
            }
            return Optional.of(output);
        } catch (IOException e) {
            log.error("Could not run the downloaded jar", e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Could not remove {}", path, e);
        }
    }
}
