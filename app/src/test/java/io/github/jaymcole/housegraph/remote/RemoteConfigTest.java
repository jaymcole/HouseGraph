package io.github.jaymcole.housegraph.remote;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the operator's config: its defaults, its forgiving reads, and — the part that matters —
 * the install allowlist, which is the only thing standing between a manifest fetched over the
 * network and arbitrary code being downloaded and run.
 */
class RemoteConfigTest {

    @Test
    void aMissingFileYieldsSafeDefaults() throws IOException {
        // A daemon with no config must still start, so `doctor` can explain what to write. It must
        // also default to installing nothing.
        RemoteConfig config = RemoteConfig.loadFrom(Path.of("/definitely/not/here/remote.json"));

        assertTrue(config.repositories().isEmpty());
        assertFalse(config.allowPluginInstall());
        assertEquals(RemoteConfig.DEFAULT_POLL_SECONDS, config.pollSeconds());
    }

    @Test
    void aCorruptFileIsReportedRatherThanThrown(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("remote.json");
        Files.writeString(file, "{ this is not json");

        RemoteConfig config = RemoteConfig.loadFrom(file);

        assertTrue(config.repositories().isEmpty(), "a daemon that refuses to start is harder to "
                + "diagnose on a headless box than one that starts and complains");
    }

    @Test
    void readsRepositoriesAndDefaultsTheBranch() {
        RemoteConfig config = RemoteConfig.fromJson(new JSONObject("""
                { "repositories": [
                    { "url": "git@github.com:jaymcole/my-graphs.git" },
                    { "url": "https://github.com/jaymcole/other.git", "branch": "deploy" } ] }
                """));

        assertEquals(2, config.repositories().size());
        assertEquals("main", config.repositories().get(0).branch());
        assertEquals("deploy", config.repositories().get(1).branch());
    }

    @Test
    void oneMalformedRepositoryDoesNotCostTheOthers() {
        RemoteConfig config = RemoteConfig.fromJson(new JSONObject("""
                { "repositories": [ { "branch": "main" }, { "url": "https://github.com/a/b" } ] }
                """));

        assertEquals(1, config.repositories().size());
        assertEquals("https://github.com/a/b", config.repositories().get(0).url());
    }

    @Test
    void pollingIsClampedToAFloor() {
        RemoteConfig config = RemoteConfig.fromJson(new JSONObject("{ \"pollSeconds\": 0 }"));

        assertEquals(RemoteConfig.MINIMUM_POLL_SECONDS, config.pollSeconds(),
                "below this the polling is the load");
    }

    @Test
    void installsAreRefusedUnlessBothGatesAreOpen() {
        String url = "https://github.com/jaymcole/housegraph-nodes";

        RemoteConfig listedButNotAllowed = RemoteConfig.fromJson(new JSONObject("""
                { "trustedPluginRepositories": ["https://github.com/jaymcole/housegraph-nodes"] }
                """));
        assertFalse(listedButNotAllowed.isTrustedForInstall(url),
                "allowPluginInstall defaults off, and an allowlist alone must not open the door");

        RemoteConfig allowedButNotListed = RemoteConfig.fromJson(
                new JSONObject("{ \"allowPluginInstall\": true }"));
        assertFalse(allowedButNotListed.isTrustedForInstall(url),
                "an empty allowlist allows nothing — an allowlist that guesses is not an allowlist");
    }

    @Test
    void aTrustedRepositoryMatchesHoweverItIsSpelled() {
        // The operator writes the URL the way GitHub shows it; a manifest may write it the way git
        // clones it. Both have to match, or the gate fails closed for no good reason.
        RemoteConfig config = RemoteConfig.fromJson(new JSONObject("""
                { "allowPluginInstall": true,
                  "trustedPluginRepositories": ["https://github.com/jaymcole/housegraph-nodes"] }
                """));

        assertTrue(config.isTrustedForInstall("https://github.com/jaymcole/housegraph-nodes"));
        assertTrue(config.isTrustedForInstall("https://github.com/jaymcole/housegraph-nodes.git"));
        assertTrue(config.isTrustedForInstall("https://github.com/JayMcole/HouseGraph-Nodes/"));
        assertFalse(config.isTrustedForInstall("https://github.com/someone-else/housegraph-nodes"));
    }

    @Test
    void aRepositoryKeyIsStableAndFilesystemSafe() {
        // The key names the clone directory, so the same URL must always map to the same place
        // across restarts — otherwise every daemon start would re-clone into a fresh directory.
        assertEquals("jaymcole-my-graphs",
                new RemoteConfig.Repository("git@github.com:jaymcole/my-graphs.git", "main", null).key());
        assertEquals("jaymcole-my-graphs",
                new RemoteConfig.Repository("https://github.com/jaymcole/my-graphs", "main", null).key());
        assertEquals("jaymcole-my-graphs",
                new RemoteConfig.Repository("https://github.com/JayMcole/My-Graphs.git", "main", null).key());
    }
}
