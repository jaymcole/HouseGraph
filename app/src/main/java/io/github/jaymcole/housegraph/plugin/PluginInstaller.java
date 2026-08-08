package io.github.jaymcole.housegraph.plugin;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
        Path staged = Files.createTempFile("housegraph-plugin-", ".jar.part");
        try {
            download(asset.downloadUrl(), staged);

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
            moveInto(staged, target);

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

    private static void download(String url, Path target) throws IOException, InterruptedException {
        if (!GitHubReleases.isAllowed(url)) {
            throw new InstallException("Refusing to download from " + url + " — only GitHub is allowed.");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/octet-stream")
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<Path> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() != 200) {
            throw new InstallException("Download failed with HTTP " + response.statusCode() + " for " + url);
        }
    }

    private static void moveInto(Path staged, Path target) throws IOException {
        try {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // The staging file is in the system temp directory, which is often a different volume.
            Files.copy(staged, target, StandardCopyOption.REPLACE_EXISTING);
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
