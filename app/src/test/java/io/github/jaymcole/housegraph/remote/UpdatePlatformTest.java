package io.github.jaymcole.housegraph.remote;

import io.github.jaymcole.housegraph.plugin.GitHubReleases;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers which release jar a machine is allowed to install.
 *
 * <p>The interesting cases are the refusals. A jar that merely shares an operating system carries
 * the wrong JavaFX natives and fails at launch with an error that does not say why — so the
 * architecture half of the match is the part worth pinning down, on machines the CI matrix does not
 * publish for.
 */
class UpdatePlatformTest {

    private static GitHubReleases.Asset asset(String name) {
        return new GitHubReleases.Asset(name, "https://github.com/jaymcole/HouseGraph/releases/" + name, 1024);
    }

    @Test
    void matchesTheJarBuiltForThisMachine() {
        assertEquals(Optional.of(UpdatePlatform.LINUX), UpdatePlatform.of("Linux", "amd64"));
        assertEquals(Optional.of(UpdatePlatform.MACOS), UpdatePlatform.of("Mac OS X", "aarch64"));
        assertEquals(Optional.of(UpdatePlatform.WINDOWS), UpdatePlatform.of("Windows 11", "amd64"));
    }

    @Test
    void refusesAnIntelMacBecauseTheReleasedMacJarIsAppleSilicon() {
        assertTrue(UpdatePlatform.of("Mac OS X", "x86_64").isEmpty(),
                "the macos-latest runner is Apple Silicon, so its jar carries aarch64 natives");
    }

    @Test
    void refusesAnArmLinuxBoxBecauseTheReleasedLinuxJarIsX86() {
        assertTrue(UpdatePlatform.of("Linux", "aarch64").isEmpty());
    }

    @Test
    void refusesAnOperatingSystemNoLegPublishesFor() {
        assertTrue(UpdatePlatform.of("FreeBSD", "amd64").isEmpty());
    }

    @Test
    void picksItsOwnJarOutOfAReleaseCarryingAllThree() {
        List<GitHubReleases.Asset> assets = List.of(
                asset("app-0.9.0-linux.jar"), asset("app-0.9.0-macos.jar"), asset("app-0.9.0-windows.jar"));

        assertEquals("app-0.9.0-macos.jar", UpdatePlatform.MACOS.assetIn(assets).orElseThrow().name());
        assertEquals("app-0.9.0-linux.jar", UpdatePlatform.LINUX.assetIn(assets).orElseThrow().name());
    }

    @Test
    void takesNoJarWhenItsOwnLegIsMissing() {
        // A release that stopped publishing this platform, or renamed its assets. Standing down is
        // the only safe answer: any other jar here is one this machine cannot run.
        assertTrue(UpdatePlatform.MACOS.assetIn(List.of(asset("app-0.9.0-linux.jar"))).isEmpty());
        assertTrue(UpdatePlatform.LINUX.assetIn(List.of(asset("app-0.9.0.jar"))).isEmpty());
    }
}
