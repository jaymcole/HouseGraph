package io.github.jaymcole.housegraph.remote;

import io.github.jaymcole.housegraph.plugin.GitHubReleases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the decision to replace the jar a machine is running, and the replacing itself.
 *
 * <p>Nothing here touches GitHub or spawns a JVM: the lookup, the download and the "does this jar
 * start?" check are injected, in the same shape as {@code SupervisorTest}'s fake launcher. What is
 * worth pinning down is the judgement — when to update, when to refuse, and what is left on disk when
 * a step fails — and none of it needs a network.
 */
class SelfUpdaterTest {

    private static final String REPOSITORY = "https://github.com/jaymcole/HouseGraph";

    private static GitHubReleases.Asset asset(String name) {
        return new GitHubReleases.Asset(name, "https://github.com/jaymcole/HouseGraph/releases/" + name, 4);
    }

    private static GitHubReleases.Release release(String version, String etag, String... assetNames) {
        return new GitHubReleases.Release("v" + version, version,
                List.of(assetNames).stream().map(SelfUpdaterTest::asset).toList(), etag);
    }

    /** A release carrying all three platform jars, as the release workflow publishes them. */
    private static GitHubReleases.Release fullRelease(String version, String etag) {
        return release(version, etag,
                "app-" + version + "-linux.jar", "app-" + version + "-macos.jar",
                "app-" + version + "-windows.jar");
    }

    // ---- what to do about a release -------------------------------------------------------

    @Test
    void aNewerReleaseWithAJarForThisMachineIsAnUpdate() {
        SelfUpdater.Decision decision = SelfUpdater.decide("0.8.0", null,
                fullRelease("0.9.0", "etag"), UpdatePlatform.LINUX);

        assertTrue(decision.isUpdate());
        assertEquals("0.9.0", decision.version());
        assertEquals("app-0.9.0-linux.jar", decision.asset().name());
    }

    @Test
    void theSameVersionIsNotAnUpdate() {
        SelfUpdater.Decision decision = SelfUpdater.decide("0.9.0", null,
                fullRelease("0.9.0", "etag"), UpdatePlatform.LINUX);

        assertEquals(SelfUpdater.Action.UP_TO_DATE, decision.action());
    }

    @Test
    void aNewerRunningBuildIsNotDowngraded() {
        SelfUpdater.Decision decision = SelfUpdater.decide("0.10.0", null,
                fullRelease("0.9.0", "etag"), UpdatePlatform.LINUX);

        assertEquals(SelfUpdater.Action.UP_TO_DATE, decision.action());
    }

    @Test
    void aDevelopmentBuildIsNeverUpdated() {
        // No manifest version means no honest comparison — and a working tree is not behind a
        // release in any sense that replacing its jar would fix.
        SelfUpdater.Decision decision = SelfUpdater.decide(null, null,
                fullRelease("0.9.0", "etag"), UpdatePlatform.LINUX);

        assertEquals(SelfUpdater.Action.UNKNOWN_VERSION, decision.action());
    }

    @Test
    void aReleaseThisMachineAlreadyInstalledIsNotInstalledAgain() {
        // The swap reported success but the process still came back on the old version, so the
        // supervisor is running a jar from somewhere else. Retrying would do this every hour.
        SelfUpdater.Decision decision = SelfUpdater.decide("0.8.0", "0.9.0",
                fullRelease("0.9.0", "etag"), UpdatePlatform.LINUX);

        assertEquals(SelfUpdater.Action.ALREADY_APPLIED, decision.action());
    }

    @Test
    void aReleaseWithNoJarForThisPlatformIsLeftAlone() {
        SelfUpdater.Decision decision = SelfUpdater.decide("0.8.0", null,
                release("0.9.0", "etag", "app-0.9.0-linux.jar"), UpdatePlatform.MACOS);

        assertEquals(SelfUpdater.Action.NO_ASSET, decision.action());
    }

    // ---- checking ---------------------------------------------------------------------------

    @Test
    void anUnchangedReleaseCostsNothingAndChangesNothing(@TempDir Path directory) throws IOException {
        RemoteState state = stateWith(directory, "etag-1");
        SelfUpdater updater = updater(directory.resolve("housegraph.jar"), "0.9.0", state,
                (url, etag) -> {
                    assertEquals("etag-1", etag, "the stored ETag has to be replayed, or the check "
                            + "spends GitHub's hourly budget on an answer it already has");
                    return Optional.empty();
                },
                (a, target) -> {
                    throw new AssertionError("nothing to download");
                },
                jar -> Optional.of("housegraph 0.9.0"));

        assertEquals(SelfUpdater.Action.UP_TO_DATE, updater.check().action());
    }

    @Test
    void anEtagIsRememberedOnlyWhenThereIsNothingLeftToDo(@TempDir Path directory) throws IOException {
        RemoteState state = stateWith(directory, null);
        SelfUpdater upToDate = updater(directory.resolve("housegraph.jar"), "0.9.0", state,
                (url, etag) -> Optional.of(fullRelease("0.9.0", "etag-new")),
                (a, target) -> {
                    throw new AssertionError("nothing to download");
                },
                jar -> Optional.of("housegraph 0.9.0"));

        upToDate.check();
        assertEquals(Optional.of("etag-new"), state.releaseEtag());
    }

    @Test
    void anEtagIsNotRememberedWhileAnUpdateIsStillPending(@TempDir Path directory) throws IOException {
        // Recording it here would turn the next check into a 304 and lose the update entirely if the
        // download that follows fails.
        RemoteState state = stateWith(directory, null);
        SelfUpdater updater = updater(directory.resolve("housegraph.jar"), "0.8.0", state,
                (url, etag) -> Optional.of(fullRelease("0.9.0", "etag-new")),
                (a, target) -> {
                    throw new AssertionError("check() must not download");
                },
                jar -> Optional.of("housegraph 0.9.0"));

        assertTrue(updater.check().isUpdate());
        assertTrue(state.releaseEtag().isEmpty());
    }

    @Test
    void aFailedLookupIsReportedRatherThanThrown(@TempDir Path directory) throws IOException {
        SelfUpdater updater = updater(directory.resolve("housegraph.jar"), "0.8.0", stateWith(directory, null),
                (url, etag) -> {
                    throw new GitHubReleases.LookupException("GitHub rate limit reached");
                },
                (a, target) -> {
                    throw new AssertionError("nothing to download");
                },
                jar -> Optional.of("housegraph 0.9.0"));

        SelfUpdater.Decision decision = updater.check();
        assertEquals(SelfUpdater.Action.FAILED, decision.action());
        assertTrue(decision.message().contains("rate limit"));
    }

    @Test
    void aMachineNotRunningFromAJarSaysSoBeforeItAsksGitHub(@TempDir Path directory) throws IOException {
        SelfUpdater updater = new SelfUpdater(new RemoteConfig.SelfUpdate(true, REPOSITORY, 3600),
                stateWith(directory, null), null, "0.8.0", UpdatePlatform.LINUX,
                (url, etag) -> {
                    throw new AssertionError("must not reach the network");
                },
                (a, target) -> {
                    throw new AssertionError("nothing to download");
                },
                jar -> Optional.empty());

        assertTrue(updater.canApply().isPresent());
        assertEquals(SelfUpdater.Action.UNSUPPORTED, updater.check().action());
    }

    // ---- installing -------------------------------------------------------------------------

    @Test
    void installingKeepsTheOldJarBesideTheNewOne(@TempDir Path directory) throws IOException {
        RemoteState state = stateWith(directory, "etag-1");
        Path jar = jarAt(directory, "old build");
        SelfUpdater updater = updater(jar, "0.8.0", state,
                (url, etag) -> Optional.of(fullRelease("0.9.0", "etag-new")),
                (a, target) -> Files.writeString(target, "new build"),
                staged -> Optional.of("housegraph 0.9.0"));

        SelfUpdater.Decision decision = updater.check();
        assertTrue(updater.apply(decision));

        assertEquals("new build", Files.readString(jar));
        assertEquals("old build", Files.readString(directory.resolve("housegraph.jar.previous")),
                "the replaced build is the only rollback a machine with nobody at the keyboard has");
        assertFalse(Files.exists(directory.resolve("housegraph.jar.new")), "the staging file is cleaned up");
        assertEquals(Optional.of("0.9.0"), state.appliedVersion());
        assertTrue(state.releaseEtag().isEmpty(),
                "the next check has to be a full one, so a swap that did not take is noticed");
    }

    @Test
    void aJarThatWillNotStartIsNotInstalled(@TempDir Path directory) throws IOException {
        RemoteState state = stateWith(directory, null);
        Path jar = jarAt(directory, "old build");
        SelfUpdater updater = updater(jar, "0.8.0", state,
                (url, etag) -> Optional.of(fullRelease("0.9.0", "etag-new")),
                (a, target) -> Files.writeString(target, "truncated"),
                staged -> Optional.empty());

        assertFalse(updater.apply(updater.check()));
        assertEquals("old build", Files.readString(jar));
        assertTrue(state.appliedVersion().isEmpty());
        assertFalse(Files.exists(directory.resolve("housegraph.jar.new")));
    }

    @Test
    void aJarReportingSomeOtherVersionIsNotInstalled(@TempDir Path directory) throws IOException {
        // The release said 0.9.0 and the jar behind it says something else: either the asset is not
        // what the release advertised, or the naming convention has moved. Either way, don't install.
        Path jar = jarAt(directory, "old build");
        SelfUpdater updater = updater(jar, "0.8.0", stateWith(directory, null),
                (url, etag) -> Optional.of(fullRelease("0.9.0", "etag-new")),
                (a, target) -> Files.writeString(target, "some other build"),
                staged -> Optional.of("housegraph 0.7.1"));

        assertFalse(updater.apply(updater.check()));
        assertEquals("old build", Files.readString(jar));
    }

    @Test
    void aDownloadThatFailsLeavesAWorkingJarBehind(@TempDir Path directory) throws IOException {
        Path jar = jarAt(directory, "old build");
        SelfUpdater updater = updater(jar, "0.8.0", stateWith(directory, null),
                (url, etag) -> Optional.of(fullRelease("0.9.0", "etag-new")),
                (a, target) -> {
                    throw new IOException("connection reset");
                },
                staged -> Optional.of("housegraph 0.9.0"));

        assertFalse(updater.apply(updater.check()));
        assertEquals("old build", Files.readString(jar));
    }

    @Test
    void theSwapIsAtomicOverTheJarItself(@TempDir Path directory) throws IOException {
        Path jar = jarAt(directory, "old build");
        Path staged = directory.resolve("housegraph.jar.new");
        Files.writeString(staged, "new build");

        SelfUpdater.install(staged, jar);

        assertEquals("new build", Files.readString(jar));
        assertEquals("old build", Files.readString(directory.resolve("housegraph.jar" + SelfUpdater.PREVIOUS_SUFFIX)));
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private static RemoteState stateWith(Path directory, String etag) {
        RemoteState state = RemoteState.loadFrom(directory.resolve("remote-state.json"));
        state.recordReleaseEtag(etag);
        return state;
    }

    private static Path jarAt(Path directory, String contents) throws IOException {
        Path jar = directory.resolve("housegraph.jar");
        Files.write(jar, contents.getBytes(StandardCharsets.UTF_8));
        return jar;
    }

    private static SelfUpdater updater(Path jar, String currentVersion, RemoteState state,
                                       SelfUpdater.Lookup lookup, SelfUpdater.Downloader downloader,
                                       SelfUpdater.Verifier verifier) {
        return new SelfUpdater(new RemoteConfig.SelfUpdate(true, REPOSITORY, 3600), state, jar,
                currentVersion, UpdatePlatform.LINUX, lookup, downloader, verifier);
    }
}
