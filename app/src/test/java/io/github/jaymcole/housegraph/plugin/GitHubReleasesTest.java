package io.github.jaymcole.housegraph.plugin;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the pure parts — URL parsing, host restriction, asset selection, release parsing. The HTTP
 * call itself is one thin method around them and is left to manual verification rather than a fake
 * server, in keeping with this project's "no real network in tests" rule.
 */
class GitHubReleasesTest {

    // --- Repository URL parsing and the host restriction ----------------------------------------

    @Test
    void parsesTheUsualRepositoryUrlShapes() {
        assertArrayEquals(new String[]{"jaymcole", "HouseGraph"},
                GitHubReleases.ownerAndRepo("https://github.com/jaymcole/HouseGraph"));
        assertArrayEquals(new String[]{"jaymcole", "HouseGraph"},
                GitHubReleases.ownerAndRepo("https://github.com/jaymcole/HouseGraph/"));
        assertArrayEquals(new String[]{"jaymcole", "HouseGraph"},
                GitHubReleases.ownerAndRepo("https://github.com/jaymcole/HouseGraph.git"),
                "a clone URL is what people copy out of the GitHub UI");
    }

    @Test
    void refusesAnyHostThatIsNotGitHub() {
        // A repository URL can arrive from a save file, which is untrusted input proposing a code
        // download. Bounding the host is the cheapest meaningful restriction available.
        assertThrows(GitHubReleases.LookupException.class,
                () -> GitHubReleases.ownerAndRepo("https://evil.example.com/owner/repo"));
        assertThrows(GitHubReleases.LookupException.class,
                () -> GitHubReleases.ownerAndRepo("file:///C:/windows/system32"));
        assertFalse(GitHubReleases.isAllowed("https://github.evil.com/a/b"),
                "a lookalike host must not pass a prefix check");
        assertTrue(GitHubReleases.isAllowed("https://github.com/a/b"));
    }

    @Test
    void refusesAUrlWithoutBothOwnerAndRepo() {
        assertThrows(GitHubReleases.LookupException.class, () -> GitHubReleases.ownerAndRepo("https://github.com/owner"));
        assertThrows(GitHubReleases.LookupException.class, () -> GitHubReleases.ownerAndRepo(""));
        assertThrows(GitHubReleases.LookupException.class, () -> GitHubReleases.ownerAndRepo(null));
    }

    // --- Asset selection ------------------------------------------------------------------------

    @Test
    void prefersShadedJarsBecauseAPlainOneLacksItsDependencies() {
        List<GitHubReleases.Asset> jars = GitHubReleases.jarAssets(new JSONArray(List.of(
                asset("widgets-1.0.0.jar", 1000),
                asset("widgets-1.0.0-all.jar", 9000),
                asset("widgets-1.0.0-sources.jar", 500))));

        assertEquals(1, jars.size(), "the plain jar is the unshaded build of the same thing");
        assertEquals("widgets-1.0.0-all.jar", jars.get(0).name());
        assertEquals(9000, jars.get(0).sizeBytes(), "the size is shown in the install confirmation");
    }

    @Test
    void fallsBackToAPlainJarWhenNothingIsShaded() {
        List<GitHubReleases.Asset> jars = GitHubReleases.jarAssets(new JSONArray(List.of(
                asset("notes.txt", 10), asset("widgets.jar", 700))));

        assertEquals(List.of("widgets.jar"), jars.stream().map(GitHubReleases.Asset::name).toList());
    }

    @Test
    void sourcesAndJavadocJarsAreNotNodeLibraries() {
        List<GitHubReleases.Asset> jars = GitHubReleases.jarAssets(new JSONArray(List.of(
                asset("widgets-1.0.0-sources.jar", 100), asset("widgets-1.0.0-javadoc.jar", 100))));

        assertTrue(jars.isEmpty());
    }

    @Test
    void findsNoJarsWhenTheReleaseHasNone() {
        assertTrue(GitHubReleases.jarAssets(new JSONArray(List.of(asset("notes.txt", 10)))).isEmpty());
        assertTrue(GitHubReleases.jarAssets(new JSONArray()).isEmpty());
        assertTrue(GitHubReleases.jarAssets(null).isEmpty());
    }

    // --- A repository publishing several libraries (a monorepo) ---------------------------------

    @Test
    void aMonorepoReleaseKeepsEveryLibrarysJar() {
        GitHubReleases.Release release = GitHubReleases.parse(new JSONObject()
                .put("tag_name", "v1.0.0")
                .put("assets", new JSONArray(List.of(
                        asset("housegraph-discord-1.0.0-all.jar", 5_000_000),
                        asset("housegraph-camera-1.0.0-all.jar", 900_000),
                        asset("housegraph-iot-1.0.0-all.jar", 40_000)))), null);

        assertEquals(3, release.assets().size());
        assertTrue(release.hasSeveralLibraries(), "the user has to be asked which one they meant");
    }

    @Test
    void aLibraryIsMatchedToItsOwnJarByTheNamingConvention() {
        // This is what makes updating a monorepo library work: it must take the jar it already has,
        // not whichever was attached first.
        GitHubReleases.Release release = GitHubReleases.parse(new JSONObject()
                .put("tag_name", "v1.0.0")
                .put("assets", new JSONArray(List.of(
                        asset("housegraph-discord-1.0.0-all.jar", 5_000_000),
                        asset("housegraph-camera-1.0.0-all.jar", 900_000)))), null);

        assertEquals("housegraph-camera-1.0.0-all.jar",
                release.assetFor("housegraph-camera").orElseThrow().name());
        assertEquals("housegraph-discord-1.0.0-all.jar",
                release.assetFor("housegraph-discord").orElseThrow().name());
        assertTrue(release.assetFor("housegraph-nothere").isEmpty());
    }

    @Test
    void aSingleLibraryReleaseNeedsNoNamingConventionAtAll() {
        // Someone forking the template shouldn't have to think about asset naming.
        GitHubReleases.Release release = GitHubReleases.parse(new JSONObject()
                .put("tag_name", "v1.0.0")
                .put("assets", new JSONArray(List.of(asset("anything-you-like.jar", 100)))), null);

        assertFalse(release.hasSeveralLibraries());
        assertEquals("anything-you-like.jar", release.assetFor("housegraph-widgets").orElseThrow().name(),
                "with one jar there is nothing to disambiguate");
    }

    // --- Release parsing ------------------------------------------------------------------------

    @Test
    void stripsTheVeeFromATagSoItComparesAgainstAManifestVersion() {
        JSONObject release = new JSONObject()
                .put("tag_name", "v1.2.3")
                .put("assets", new JSONArray(List.of(asset("widgets-all.jar", 100))));

        GitHubReleases.Release parsed = GitHubReleases.parse(release, "\"abc\"");

        assertEquals("v1.2.3", parsed.tagName(), "the tag is kept as-is for display");
        assertEquals("1.2.3", parsed.version(), "...but the version has to match the manifest's");
        assertEquals("\"abc\"", parsed.etag(),
                "the ETag is stored so the next check can be conditional, and a 304 costs no rate limit");
    }

    @Test
    void aReleaseWithNoJarSaysWhatIsWrongWithTheLibrarysBuild() {
        JSONObject release = new JSONObject().put("tag_name", "v1.0.0").put("assets", new JSONArray());

        GitHubReleases.LookupException failure =
                assertThrows(GitHubReleases.LookupException.class, () -> GitHubReleases.parse(release, null));
        assertTrue(failure.getMessage().contains("release"),
                "the message has to point at the library's release workflow, not just say 'failed'");
    }

    private static JSONObject asset(String name, long size) {
        return new JSONObject()
                .put("name", name)
                .put("size", size)
                .put("browser_download_url", "https://objects.githubusercontent.com/" + name);
    }
}
