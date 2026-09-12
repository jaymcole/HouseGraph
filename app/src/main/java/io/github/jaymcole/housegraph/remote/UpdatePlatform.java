package io.github.jaymcole.housegraph.remote;

import io.github.jaymcole.housegraph.plugin.GitHubReleases;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Which jar from a HouseGraph release this machine is able to run.
 *
 * <h2>Why a release has several jars in the first place</h2>
 * The shaded jar bundles JavaFX's <em>native</em> libraries for whatever machine built it, so there
 * is no one jar that runs everywhere: {@code .github/workflows/release.yml} builds on Linux, macOS
 * and Windows and attaches all three, named {@code app-<version>-<platform>.jar}. Picking the wrong
 * one produces a native-library failure at launch that does not obviously say why — which is exactly
 * the mistake an unattended updater must not make on the operator's behalf.
 *
 * <h2>Architecture is part of the match, not a detail</h2>
 * Each release leg also pins an architecture: the GitHub-hosted macOS runner is Apple Silicon and
 * the Linux and Windows runners are x86-64, so the published macOS jar carries {@code aarch64}
 * natives and the other two {@code x86-64}. An Intel Mac or an ARM Linux box is therefore a machine
 * <b>no published jar fits</b>, and this enum says so — {@link #current()} is empty — rather than
 * handing back the jar that merely shares an operating system. Refusing to update is recoverable;
 * replacing a working jar with one that cannot start is not.
 */
public enum UpdatePlatform {

    /** The {@code ubuntu-latest} leg: x86-64 Linux natives. */
    LINUX("linux", Set.of("amd64", "x86_64", "x64")),

    /** The {@code macos-latest} leg: Apple Silicon natives. An Intel Mac is not covered by it. */
    MACOS("macos", Set.of("aarch64", "arm64")),

    /** The {@code windows-latest} leg: x86-64 Windows natives. */
    WINDOWS("windows", Set.of("amd64", "x86_64", "x64"));

    private final String assetToken;
    private final Set<String> architectures;

    UpdatePlatform(String assetToken, Set<String> architectures) {
        this.assetToken = assetToken;
        this.architectures = architectures;
    }

    /** The suffix the release workflow gives this leg's jar, before {@code .jar}. */
    public String assetToken() {
        return assetToken;
    }

    /**
     * The platform whose published jar this machine can run.
     *
     * @return the platform, or empty when no release leg matches this OS and architecture
     */
    public static Optional<UpdatePlatform> current() {
        return of(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /**
     * The platform for an explicit OS and architecture. Pure, so the matching is testable on any
     * machine — including the combinations that are deliberately unsupported.
     *
     * @param osName the {@code os.name} system property
     * @param osArch the {@code os.arch} system property
     * @return the matching platform, or empty when the OS is unknown or its published jar targets a
     *         different architecture
     */
    public static Optional<UpdatePlatform> of(String osName, String osArch) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        UpdatePlatform platform;
        if (name.contains("mac") || name.contains("darwin")) {
            platform = MACOS;
        } else if (name.contains("win")) {
            platform = WINDOWS;
        } else if (name.contains("linux")) {
            platform = LINUX;
        } else {
            return Optional.empty();
        }
        return platform.architectures.contains(arch) ? Optional.of(platform) : Optional.empty();
    }

    /**
     * This platform's jar among a release's assets.
     *
     * <p>Matched on the {@code -<platform>.jar} suffix and nothing looser. A release that stopped
     * publishing this leg, or renamed its assets, yields empty and the updater stands down — better
     * than downloading whichever jar happened to sort first.
     *
     * @param assets the release's attached jars
     * @return this platform's jar, or empty when the release does not carry one
     */
    public Optional<GitHubReleases.Asset> assetIn(List<GitHubReleases.Asset> assets) {
        String suffix = "-" + assetToken + ".jar";
        return assets.stream()
                .filter(asset -> asset.name().toLowerCase(Locale.ROOT).endsWith(suffix))
                .findFirst();
    }
}
