package io.github.jaymcole.housegraph.remote;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the sync against a <b>real git repository on disk</b>, served over a {@code file://}
 * URL. No network, no GitHub, deterministic — which {@code docs/architecture/testing.md} requires —
 * while still running the actual clone/fetch/reset commands rather than a mock of them. Mocking git
 * here would test only that the arguments were spelled the way the test expected.
 */
class GraphRepositoryTest {

    @BeforeAll
    static void requireGit() {
        assumeTrue(GitCommand.isAvailable(), "git is not installed");
    }

    /** A bare repository with one commit, and a checkout to push further commits from. */
    private record Fixture(Path bare, Path work) {

        String url() {
            return bare.toUri().toString();
        }

        void commit(String file, String content, String message) throws IOException {
            Path target = work.resolve(file);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            GitCommand git = GitCommand.in(work);
            git.run("add", "-A").orThrow("add");
            git.run("commit", "-m", message).orThrow("commit");
            git.run("push", "origin", "main").orThrow("push");
        }
    }

    private static Fixture fixture(Path root) throws IOException {
        Path bare = Files.createDirectory(root.resolve("origin.git"));
        GitCommand.in(bare).run("init", "--bare", "--initial-branch=main", ".").orThrow("init bare");

        Path work = Files.createDirectory(root.resolve("work"));
        GitCommand git = GitCommand.in(work);
        git.run("init", "--initial-branch=main", ".").orThrow("init");
        // Identity has to be set locally: a CI machine has no global git config, and a commit
        // without one fails in a way that looks nothing like the thing under test.
        git.run("config", "user.email", "test@example.invalid").orThrow("config email");
        git.run("config", "user.name", "HouseGraph Test").orThrow("config name");
        git.run("remote", "add", "origin", bare.toString()).orThrow("remote add");

        Fixture fixture = new Fixture(bare, work);
        fixture.commit(RepoManifest.FILE_NAME,
                "{\"graphs\":[{\"file\":\"graphs/porch.json\"}]}", "initial");
        return fixture;
    }

    private static GraphRepository repository(Fixture fixture, Path root) {
        return new GraphRepository(
                new RemoteConfig.Repository(fixture.url(), "main", null),
                root.resolve("clone"));
    }

    @Test
    void clonesOnFirstSyncAndReportsTheCommit(@TempDir Path root) throws IOException {
        Fixture fixture = fixture(root);
        GraphRepository repository = repository(fixture, root);

        assertFalse(repository.isCloned());
        String sha = repository.sync();

        assertTrue(repository.isCloned());
        assertEquals(sha, repository.remoteHead().orElseThrow());
        assertTrue(Files.isRegularFile(repository.cloneDirectory().resolve(RepoManifest.FILE_NAME)));
    }

    @Test
    void remoteHeadSeesANewCommitWithoutTouchingTheMirror(@TempDir Path root) throws IOException {
        // This is the poll. It must notice the change while leaving the clone alone, because the
        // steady state — nothing pushed — has to cost no disk writes at all.
        Fixture fixture = fixture(root);
        GraphRepository repository = repository(fixture, root);
        String first = repository.sync();

        fixture.commit("graphs/porch.json", "{}", "add a graph");

        assertNotEquals(first, repository.remoteHead().orElseThrow());
        assertEquals(first, repository.localHead().orElseThrow(), "the mirror hasn't moved yet");
    }

    @Test
    void syncBringsTheMirrorUpToTheNewCommit(@TempDir Path root) throws IOException {
        Fixture fixture = fixture(root);
        GraphRepository repository = repository(fixture, root);
        repository.sync();

        fixture.commit("graphs/porch.json", "{\"version\":2}", "add a graph");
        String synced = repository.sync();

        assertEquals(repository.remoteHead().orElseThrow(), synced);
        assertTrue(Files.isRegularFile(repository.cloneDirectory().resolve("graphs/porch.json")));
    }

    @Test
    void aLocalEditIsDiscardedRatherThanCausingAConflict(@TempDir Path root) throws IOException {
        // The reason update() is reset --hard and not pull. A conflict on an unattended machine is a
        // silent hang with nobody to resolve it; the mirror exists to reflect what was pushed.
        Fixture fixture = fixture(root);
        GraphRepository repository = repository(fixture, root);
        repository.sync();
        Files.writeString(repository.cloneDirectory().resolve(RepoManifest.FILE_NAME), "{\"graphs\":[]}");

        fixture.commit(RepoManifest.FILE_NAME, "{\"graphs\":[{\"file\":\"graphs/hall.json\"}]}", "upstream edit");
        repository.sync();

        assertTrue(Files.readString(repository.cloneDirectory().resolve(RepoManifest.FILE_NAME))
                .contains("hall.json"));
    }

    @Test
    void aFileDeletedUpstreamStopsExistingLocally(@TempDir Path root) throws IOException {
        // reset --hard alone leaves untracked files behind, so a graph removed from the repository
        // would keep running. The clean -fd is what actually retires it.
        Fixture fixture = fixture(root);
        GraphRepository repository = repository(fixture, root);
        fixture.commit("graphs/porch.json", "{}", "add a graph");
        repository.sync();
        assertTrue(Files.isRegularFile(repository.cloneDirectory().resolve("graphs/porch.json")));

        GitCommand git = GitCommand.in(fixture.work());
        git.run("rm", "graphs/porch.json").orThrow("rm");
        git.run("commit", "-m", "remove the graph").orThrow("commit");
        git.run("push", "origin", "main").orThrow("push");
        repository.sync();

        assertFalse(Files.exists(repository.cloneDirectory().resolve("graphs/porch.json")));
    }

    @Test
    void anUnknownBranchIsReportedAsAbsentRatherThanThrowing(@TempDir Path root) throws IOException {
        Fixture fixture = fixture(root);
        GraphRepository repository = new GraphRepository(
                new RemoteConfig.Repository(fixture.url(), "no-such-branch", null),
                root.resolve("clone"));

        assertEquals(Optional.empty(), repository.remoteHead());
    }

    @Test
    void anUnreachableRemoteFails(@TempDir Path root) {
        GraphRepository repository = new GraphRepository(
                new RemoteConfig.Repository(root.resolve("nothing-here.git").toUri().toString(), "main", null),
                root.resolve("clone"));

        org.junit.jupiter.api.Assertions.assertThrows(GitCommand.GitException.class, repository::remoteHead);
    }
}
