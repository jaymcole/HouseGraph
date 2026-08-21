package io.github.jaymcole.housegraph.plugin;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads a node library's jar, checks it is safe to load, and records it in the catalog.
 *
 * <h2>What "safe" means here, honestly</h2>
 * Not sandboxed. A node library runs in this JVM with the user's full privileges and can read the
 * secrets store, the filesystem and the network. There is no sandbox available on Java 21+ —
 * {@code SecurityManager} is gone and JPMS has no permission model — so installing a library is
 * exactly as dangerous as running any program you downloaded, and the UI says so rather than
 * implying a boundary that doesn't exist.
 * <p>
 * The validation here is therefore not a security boundary. It catches the three ways a library
 * can be built wrong that would otherwise produce baffling runtime behaviour, and turns them into
 * one clear message at install time. See {@link #validate}.
 */
public final class PluginInstaller {

    private static final Logger log = Log.get(PluginInstaller.class);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Raised when a download or a validation check fails, with a message meant for the user. */
    public static class InstallException extends RuntimeException {
        public InstallException(String message) {
            super(message);
        }

        public InstallException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Reports download progress as it happens, so a caller with a UI can show it. {@code totalBytes}
     * is the asset size GitHub reported when the release was looked up, not a value read back off
     * the HTTP response — every caller already has it before the download starts, and a release
     * asset's size doesn't change out from under a running install.
     *
     * <p>{@code totalBytes <= 0} means the size wasn't known; a caller with a determinate progress
     * bar should treat that as "stay indeterminate" rather than dividing by it.
     */
    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NONE = (bytesRead, totalBytes) -> { };

        void onProgress(long bytesRead, long totalBytes);
    }

    private PluginInstaller() {
    }

    /**
     * Downloads and installs the latest release of a repository.
     *
     * @param repositoryUrl the library's GitHub repository
     * @param catalog       updated in place and saved on success
     * @return the installed entry
     */
    public static PluginCatalog.Installed install(String repositoryUrl, PluginCatalog catalog)
            throws IOException, InterruptedException {
        GitHubReleases.Release release = GitHubReleases.latest(repositoryUrl, null)
                .orElseThrow(() -> new InstallException("No release information returned for " + repositoryUrl));
        if (release.hasSeveralLibraries()) {
            throw new InstallException("Release " + release.tagName() + " publishes "
                    + release.assets().size() + " node libraries; pick one rather than installing blindly.");
        }
        return install(repositoryUrl, release, release.assets().get(0), catalog);
    }

    /**
     * Downloads and installs one <em>named</em> library from a repository's latest release, which is
     * also how an update is performed: the newest release is fetched and recorded over the old entry.
     *
     * <p>This exists because {@link #install(String, PluginCatalog)} refuses a release carrying
     * several libraries, and every first-party release does — {@code housegraph-nodes} is a monorepo
     * that attaches a jar per library. Naming the wanted id resolves that without a human choosing,
     * which is what lets an unattended daemon and an auto-install both work against a monorepo.
     * {@code PluginWindow} had the same logic buried in a JavaFX class where nothing headless could
     * reach it.
     *
     * @param repositoryUrl  the library's GitHub repository
     * @param wantedPluginId which library to take when the release publishes several
     * @param catalog        updated in place and saved on success
     * @return the installed entry
     * @throws InstallException when the latest release carries no jar for {@code wantedPluginId}
     */
    public static PluginCatalog.Installed install(String repositoryUrl, String wantedPluginId, PluginCatalog catalog)
            throws IOException, InterruptedException {
        GitHubReleases.Release release = GitHubReleases.latest(repositoryUrl, null)
                .orElseThrow(() -> new InstallException("No release information returned for " + repositoryUrl));
        GitHubReleases.Asset asset = release.assetFor(wantedPluginId)
                .orElseThrow(() -> new InstallException("Release " + release.tagName() + " of " + repositoryUrl
                        + " has no jar for \"" + wantedPluginId + "\"."));
        return install(repositoryUrl, release, asset, catalog);
    }

    /**
     * Installs one library from an already-resolved release, so a caller that showed a confirmation
     * can reuse it — and so a repository publishing several libraries installs the chosen one rather
     * than whichever happened to be listed first.
     *
     * @param asset the specific jar to install from {@code release}
     */
    public static PluginCatalog.Installed install(String repositoryUrl,
                                                  GitHubReleases.Release release,
                                                  GitHubReleases.Asset asset,
                                                  PluginCatalog catalog) throws IOException, InterruptedException {
        return install(repositoryUrl, release, asset, catalog, ProgressListener.NONE);
    }

    /**
     * Same as {@link #install(String, GitHubReleases.Release, GitHubReleases.Asset, PluginCatalog)},
     * reporting download progress to {@code progress} as bytes arrive.
     */
    public static PluginCatalog.Installed install(String repositoryUrl,
                                                  GitHubReleases.Release release,
                                                  GitHubReleases.Asset asset,
                                                  PluginCatalog catalog,
                                                  ProgressListener progress) throws IOException, InterruptedException {
        Path staged = Files.createTempFile("housegraph-plugin-", ".jar.part");
        try {
            download(asset.downloadUrl(), staged, asset.sizeBytes(), progress);

            PluginManifest manifest = PluginManifest.read(staged)
                    .orElseThrow(() -> new InstallException(
                            "That jar has no valid " + PluginManifest.ENTRY + ", so it isn't a HouseGraph node "
                                    + "library. Check the release attached the right file."));

            List<String> problems = validate(staged);
            if (!problems.isEmpty()) {
                throw new InstallException("\"" + manifest.id() + "\" can't be loaded:\n  - "
                        + String.join("\n  - ", problems));
            }

            String sha256 = sha256(staged);
            // Version-stamped, because a class loader holds an open handle on a jar and on Windows an
            // open jar can be neither deleted nor overwritten. Installing an update therefore writes a
            // new path instead of replacing one that may be in use.
            Path target = catalog.pluginsRoot()
                    .resolve(PluginCatalog.sanitize(manifest.id()))
                    .resolve(PluginCatalog.sanitize(manifest.version()))
                    .resolve(PluginCatalog.sanitize(manifest.id()) + ".jar");
            Files.createDirectories(target.getParent());
            if (Files.isRegularFile(target) && matchesRecordedHash(target, sha256)) {
                // Byte-identical to what's already on disk at this exact version — most often because
                // it was removed or updated earlier this session while one of its nodes was still on
                // the canvas, which defers the physical cleanup (see pruneSupersededVersions) without
                // touching the catalog entry's file. Nothing needs to move, so there's nothing for a
                // still-open handle to block: reinstating the catalog entry below is the whole job,
                // and that alone cancels the deferred cleanup, since pruning only ever deletes what
                // the catalog no longer lists.
                log.info("\"{}\" {} is already on disk and unchanged; skipping the download's jar in favor "
                        + "of the existing one", manifest.id(), manifest.version());
            } else {
                moveInto(staged, target);
            }

            PluginCatalog.Installed installed =
                    PluginCatalog.Installed.fromManifest(manifest, repositoryUrl, sha256);
            catalog.put(installed);
            catalog.save();
            log.info("Installed node library \"{}\" {} from {}", installed.id(), installed.version(), repositoryUrl);
            return installed;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /**
     * What came of running an {@link AutoInstallPlan}.
     *
     * @param installed ids that were fetched successfully, in the order they were done
     * @param failed    ids that could not be fetched, each with the reason, for one summary log line
     */
    public record AutoInstallOutcome(List<String> installed, List<String> failed) {

        public AutoInstallOutcome {
            installed = List.copyOf(installed);
            failed = List.copyOf(failed);
        }

        public boolean anyInstalled() {
            return !installed.isEmpty();
        }
    }

    /**
     * Carries out a plan the user's trust settings already approved, one library at a time.
     *
     * <p><b>Never call this on a UI thread.</b> Each action is a network fetch of an arbitrarily large
     * jar; the caller is expected to be on a worker.
     *
     * <p>A failure is collected rather than thrown. The caller's next move is to open the graph
     * regardless — a library that could not be fetched simply leaves its nodes as placeholders, which
     * is already safe and already what happens without this feature — so one library being
     * unreachable must not abandon the others or the open itself. An interrupt is the exception: it
     * means the app is going away, so the loop stops immediately with what it has.
     *
     * @param plan    the approved actions
     * @param catalog updated and saved as each install completes
     * @return what succeeded and what did not
     */
    public static AutoInstallOutcome apply(AutoInstallPlan plan, PluginCatalog catalog) {
        List<String> installed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (AutoInstallPlan.Action action : plan.actions()) {
            try {
                log.info("Auto-{} node library \"{}\" from {}",
                        action.kind() == AutoInstallPlan.Kind.UPDATE ? "updating" : "installing",
                        action.pluginId(), action.repository());
                PluginCatalog.Installed result = install(action.repository(), action.pluginId(), catalog);
                installed.add(result.id() + " " + result.version());
            } catch (IOException | RuntimeException e) {
                log.error("Could not auto-install \"{}\" from {}", action.pluginId(), action.repository(), e);
                failed.add(action.pluginId() + " (" + e.getMessage() + ")");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new AutoInstallOutcome(installed, failed);
    }

    /**
     * The three build mistakes worth catching before a jar is ever handed to a class loader. Each is
     * a one-line check that converts a confusing runtime symptom into a clear install-time message.
     *
     * @param jar the downloaded jar
     * @return the problems found; empty when the jar is loadable
     */
    static List<String> validate(Path jar) {
        List<String> problems = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (zip.getEntry("io/github/jaymcole/housegraph/graph/BaseNode.class") != null) {
                // Its nodes would extend *its* BaseNode, so every one of them fails the host's
                // BaseNode.isAssignableFrom check during discovery and simply never appears, with
                // nothing in the log to explain why.
                problems.add("it bundles housegraph-api. Depend on it with compileOnly, not implementation.");
            }
            if (hasEntryUnder(zip, "org/slf4j/")) {
                // A second SLF4J binding, initialised separately, routing into a LogManager with no
                // sinks attached: the library's logs vanish silently.
                problems.add("it bundles slf4j-api, which would silently swallow all of its own logging. "
                        + "It comes from housegraph-api; mark that compileOnly.");
            }
            if (zip.getEntry("META-INF/services/org.slf4j.spi.SLF4JServiceProvider") != null) {
                problems.add("it ships an SLF4J provider, which would fight with the host's.");
            }
        } catch (IOException e) {
            problems.add("it could not be read as a jar: " + e.getMessage());
        }
        return problems;
    }

    private static boolean hasEntryUnder(ZipFile zip, String prefix) {
        return zip.stream().map(ZipEntry::getName).anyMatch(name -> name.startsWith(prefix));
    }

    /** Re-checks an installed jar against the hash recorded at install time. */
    public static boolean matchesRecordedHash(Path jar, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isBlank()) {
            return true;
        }
        try {
            return expectedSha256.equalsIgnoreCase(sha256(jar));
        } catch (IOException e) {
            log.warn("Could not hash {}: {}", jar, e.toString());
            return false;
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    private static void download(String url, Path target, long totalBytes, ProgressListener progress)
            throws IOException, InterruptedException {
        if (!GitHubReleases.isAllowed(url)) {
            throw new InstallException("Refusing to download from " + url + " — only GitHub is allowed.");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/octet-stream")
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse.BodyHandler<Path> handler = responseInfo -> trackingSubscriber(target, totalBytes, progress);
        HttpResponse<Path> response = CLIENT.send(request, handler);
        if (response.statusCode() != 200) {
            throw new InstallException("Download failed with HTTP " + response.statusCode() + " for " + url);
        }
    }

    /**
     * Wraps the stock file-writing subscriber to report cumulative bytes as each chunk arrives,
     * without changing what actually gets written — every {@code onNext} is forwarded to
     * {@code delegate} untouched, after being measured. {@code java.net.http}'s only file-writing
     * subscriber ({@link HttpResponse.BodySubscribers#ofFile}) has no progress hook of its own.
     */
    private static HttpResponse.BodySubscriber<Path> trackingSubscriber(Path target, long totalBytes,
                                                                          ProgressListener progress) {
        HttpResponse.BodySubscriber<Path> delegate = HttpResponse.BodySubscribers.ofFile(target);
        long[] received = {0};
        return new HttpResponse.BodySubscriber<>() {
            @Override
            public CompletionStage<Path> getBody() {
                return delegate.getBody();
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                delegate.onSubscribe(subscription);
            }

            @Override
            public void onNext(List<ByteBuffer> item) {
                for (ByteBuffer buffer : item) {
                    received[0] += buffer.remaining();
                }
                progress.onProgress(received[0], totalBytes);
                delegate.onNext(item);
            }

            @Override
            public void onError(Throwable throwable) {
                delegate.onError(throwable);
            }

            @Override
            public void onComplete() {
                delegate.onComplete();
            }
        };
    }

    /**
     * @throws InstallException when {@code target} is a jar a class loader still has open. That
     *                          happens when this exact version was removed or updated earlier in the
     *                          same session while one of its nodes was live on the canvas: the reload
     *                          that would have released the handle is deferred until nothing is live,
     *                          same as the "Pending restart" state in the library window. Windows
     *                          can neither delete nor overwrite an open file, so the raw
     *                          {@link AccessDeniedException} is translated into a message that says
     *                          what to actually do about it, rather than surfacing as a stack trace.
     */
    private static void moveInto(Path staged, Path target) throws IOException {
        try {
            try {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // The staging file is in the system temp directory, which is often a different volume.
                Files.copy(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (AccessDeniedException e) {
            throw new InstallException("Could not write " + target.getFileName() + " — that exact version is "
                    + "still loaded, most likely because a node from it was on the canvas when it was removed "
                    + "or updated earlier this session (see \"Pending restart\" in the library window). Close "
                    + "or delete those nodes, or restart HouseGraph, then try again.", e);
        }
    }

    /**
     * Deletes on-disk library directories that no longer belong: every version of a library removed
     * from the catalog entirely (a Remove in the library window only edits the catalog, deferring
     * the jar cleanup to here so it never fights a loader that may still have the jar open), and
     * stale versions of a library still in the catalog. Called at startup, before any loader exists —
     * while a loader is open its jar can't be deleted on Windows.
     *
     * @param catalog the installed libraries
     */
    public static void pruneSupersededVersions(PluginCatalog catalog) {
        pruneRemovedLibraries(catalog);
        for (PluginCatalog.Installed installed : catalog.all()) {
            Path libraryRoot = catalog.pluginsRoot().resolve(PluginCatalog.sanitize(installed.id()));
            if (!Files.isDirectory(libraryRoot)) {
                continue;
            }
            String keep = PluginCatalog.sanitize(installed.version());
            try (var versions = Files.list(libraryRoot)) {
                versions.filter(Files::isDirectory)
                        .filter(dir -> !dir.getFileName().toString().equals(keep))
                        .forEach(PluginInstaller::deleteRecursively);
            } catch (IOException e) {
                log.warn("Could not prune old versions of \"{}\": {}", installed.id(), e.toString());
            }
        }
    }

    /**
     * Deletes every top-level library directory under {@code catalog.pluginsRoot()} whose id isn't
     * in the catalog at all, as opposed to {@link #pruneSupersededVersions}'s per-library version
     * pruning, which only ever looks at ids the catalog still has.
     */
    private static void pruneRemovedLibraries(PluginCatalog catalog) {
        Path pluginsRoot = catalog.pluginsRoot();
        if (!Files.isDirectory(pluginsRoot)) {
            return;
        }
        Set<String> keepIds = catalog.all().stream()
                .map(installed -> PluginCatalog.sanitize(installed.id()))
                .collect(Collectors.toSet());
        try (var libraries = Files.list(pluginsRoot)) {
            libraries.filter(Files::isDirectory)
                    .filter(dir -> !keepIds.contains(dir.getFileName().toString()))
                    .forEach(PluginInstaller::deleteRecursively);
        } catch (IOException e) {
            log.warn("Could not scan {} for removed libraries: {}", pluginsRoot, e.toString());
        }
    }

    private static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Could not delete {}: {}", path, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("Could not walk {} while pruning: {}", root, e.toString());
        }
    }

    /** The manifest of an already-installed jar, for re-reading what a library declares. */
    public static Optional<PluginManifest> manifestOf(Path jar) {
        return PluginManifest.read(jar);
    }
}
