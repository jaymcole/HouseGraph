package io.github.jaymcole.housegraph.plugin;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Looks up a node library's latest published release on GitHub.
 *
 * <p>Uses the JDK's own {@link HttpClient} in the same shape as {@code ReolinkClient} — a shared
 * client with a connect timeout, string bodies, {@code org.json} parsing. No new dependency is
 * needed to download a file.
 *
 * <h2>Rate limits shape the design</h2>
 * Unauthenticated {@code api.github.com} allows <b>60 requests per hour per IP</b>, and checking N
 * libraries costs N requests — easy to exhaust behind CGNAT. So: this is only ever called from an
 * explicit user action, never on a timer and never during startup. Callers should pass the stored
 * {@code ETag} back in, because <b>a conditional request answered 304 does not count against the
 * limit</b>, making a repeat check effectively free. A 403 is reported with the reset time rather
 * than as a generic failure, because "try again in 14 minutes" is actionable and "request failed"
 * is not.
 */
public final class GitHubReleases {

    private static final Logger log = Log.get(GitHubReleases.class);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * The hosts a node library may be fetched from. Bounds what a {@code repository} string —
     * which can arrive from an untrusted save file — is able to point at.
     */
    private static final List<String> ALLOWED_HOSTS =
            List.of("github.com", "www.github.com", "api.github.com", "objects.githubusercontent.com");

    /** One downloadable file attached to a release. */
    public record Asset(String name, String downloadUrl, long sizeBytes) {
    }

    /** The latest release of a repository, and the jar to install from it. */
    public record Release(String tagName, String version, Asset asset, String etag) {
    }

    /** Raised for an outcome worth showing the user verbatim, rather than a generic failure. */
    public static class LookupException extends RuntimeException {
        public LookupException(String message) {
            super(message);
        }
    }

    private GitHubReleases() {
    }

    /**
     * The latest release of {@code repositoryUrl}, or empty if the response was 304 (nothing changed
     * since {@code knownEtag}).
     *
     * @param repositoryUrl a GitHub repository URL
     * @param knownEtag     the ETag from a previous lookup, or null
     * @return the release, or empty when unchanged
     * @throws IOException          if the request fails
     * @throws InterruptedException if the calling thread is interrupted
     * @throws LookupException      for a rate limit, a missing release, or a disallowed host
     */
    public static Optional<Release> latest(String repositoryUrl, String knownEtag)
            throws IOException, InterruptedException {
        String[] ownerRepo = ownerAndRepo(repositoryUrl);
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("https://api.github.com/repos/" + ownerRepo[0] + "/" + ownerRepo[1] + "/releases/latest"))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (knownEtag != null && !knownEtag.isBlank()) {
            request.header("If-None-Match", knownEtag);
        }

        HttpResponse<String> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        if (status == 304) {
            return Optional.empty();
        }
        if (status == 403 || status == 429) {
            throw new LookupException(rateLimitMessage(response));
        }
        if (status == 404) {
            throw new LookupException("No published release found for " + ownerRepo[0] + "/" + ownerRepo[1]
                    + ". A node library has to cut a GitHub release with its jar attached.");
        }
        if (status != 200) {
            throw new LookupException("GitHub returned HTTP " + status + " for "
                    + ownerRepo[0] + "/" + ownerRepo[1]);
        }

        JSONObject json = new JSONObject(response.body());
        return Optional.of(parse(json, response.headers().firstValue("ETag").orElse(null)));
    }

    /**
     * Extracts owner and repo from a GitHub URL, rejecting anything not on an allowed host.
     * Pure, so the parsing and the host check are testable without a network.
     *
     * @param repositoryUrl the URL to parse
     * @return {@code [owner, repo]}
     */
    static String[] ownerAndRepo(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            throw new LookupException("No repository URL given");
        }
        URI uri;
        try {
            uri = URI.create(repositoryUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new LookupException("Not a valid URL: " + repositoryUrl);
        }
        String host = uri.getHost();
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new LookupException("Node libraries can only be fetched from GitHub; refusing " + repositoryUrl);
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String[] segments = path.replaceAll("^/+", "").replaceAll("/+$", "").split("/");
        if (segments.length < 2 || segments[0].isBlank() || segments[1].isBlank()) {
            throw new LookupException("Expected a URL like https://github.com/owner/repo, got " + repositoryUrl);
        }
        return new String[]{segments[0], segments[1].replaceAll("\\.git$", "")};
    }

    /** Builds a {@link Release} from the API's JSON. Pure, so asset selection is directly testable. */
    static Release parse(JSONObject releaseJson, String etag) {
        String tag = releaseJson.optString("tag_name", "");
        Asset asset = chooseAsset(releaseJson.optJSONArray("assets"));
        if (asset == null) {
            throw new LookupException("Release " + tag + " has no .jar attached. The library's release "
                    + "workflow has to upload its shaded jar as a release asset.");
        }
        // Tags are conventionally "v1.2.3" while the manifest records "1.2.3"; strip the prefix so
        // the two can be compared.
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        return new Release(tag, version, asset, etag);
    }

    /**
     * Picks the jar to install: a shaded {@code *-all.jar} if present, else the only {@code .jar}.
     * Prefers the shaded one because a plain jar almost certainly lacks the library's dependencies.
     */
    static Asset chooseAsset(JSONArray assets) {
        if (assets == null) {
            return null;
        }
        Asset onlyJar = null;
        int jarCount = 0;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject entry = assets.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            String name = entry.optString("name", "");
            if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            Asset asset = new Asset(name, entry.optString("browser_download_url", ""), entry.optLong("size", 0));
            if (name.toLowerCase(Locale.ROOT).endsWith("-all.jar")) {
                return asset;
            }
            jarCount++;
            onlyJar = asset;
        }
        if (jarCount > 1) {
            log.warn("Release has several jars and none named *-all.jar; using {}", onlyJar.name());
        }
        return onlyJar;
    }

    private static String rateLimitMessage(HttpResponse<String> response) {
        String remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse(null);
        if (!"0".equals(remaining)) {
            return "GitHub refused the request (HTTP " + response.statusCode() + ")";
        }
        return response.headers().firstValue("X-RateLimit-Reset")
                .map(reset -> {
                    long minutes = Math.max(0, (Long.parseLong(reset) - System.currentTimeMillis() / 1000) / 60);
                    return "GitHub rate limit reached — try again in about " + (minutes + 1) + " minute(s). "
                            + "Adding a GitHub token in Secrets raises the limit considerably.";
                })
                .orElse("GitHub rate limit reached — try again later.");
    }

    /** Whether a URL may be fetched from, for callers that want to check before offering to. */
    public static boolean isAllowed(String url) {
        try {
            ownerAndRepo(url);
            return true;
        } catch (LookupException e) {
            return false;
        }
    }
}
