package io.github.jaymcole.housegraph;

import java.util.Optional;

/**
 * The version of the build that is running, read from the jar manifest.
 *
 * <h2>Why the manifest rather than a constant</h2>
 * {@code shadowJar} stamps {@code Implementation-Version} with the version Gradle was given, which
 * on a release build is the tag the jar was built from. A constant in the source would say whatever
 * was last committed, not what is actually deployed — and on a machine running graphs unattended,
 * "which build is this?" is a question only the artefact itself can answer honestly.
 *
 * <p>Running from exploded classes there is no manifest, so there is no version. That is reported as
 * absence rather than as a made-up number: {@link #current()} is empty, and
 * {@code remote.SelfUpdater} treats it as "never update", because a development build is not behind
 * a release in any way worth acting on.
 */
public final class AppVersion {

    /** What to show a human when there is no manifest to read a version from. */
    public static final String DEVELOPMENT_BUILD = "(development build)";

    private AppVersion() {
    }

    /**
     * The version this jar was built as.
     *
     * @return the manifest's implementation version, or empty when running from exploded classes
     */
    public static Optional<String> current() {
        Package pkg = AppVersion.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null || version.isBlank() ? Optional.empty() : Optional.of(version);
    }

    /**
     * The same thing, as a string always safe to print.
     *
     * @return the version, or {@link #DEVELOPMENT_BUILD}
     */
    public static String describe() {
        return current().orElse(DEVELOPMENT_BUILD);
    }
}
