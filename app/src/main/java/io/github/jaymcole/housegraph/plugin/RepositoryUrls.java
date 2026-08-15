package io.github.jaymcole.housegraph.plugin;

import java.util.Collection;
import java.util.Locale;

/**
 * Compares repository URLs for {@code RemoteConfig.trustedPluginRepositories}, the operator's
 * optional narrowing of what an unattended machine may install from.
 *
 * <h2>Why this is its own class</h2>
 * The comparison has to survive the ways the same GitHub repository is routinely written down. A
 * trailing slash, a {@code .git} suffix or a difference in case would otherwise turn an accepted
 * repository into an unrecognised one, and the symptom — a library that quietly fails to install —
 * is one an operator has no reason to connect to a typo in a URL. Keeping the rule in one tested
 * place, rather than inline in {@code RemoteConfig}, is what makes that behaviour pinned rather than
 * incidental; the regex here already had to be corrected once, because the two sequential patterns
 * it grew from could not strip a {@code .git} that was followed by a slash.
 *
 * <p><b>Normalisation is for recognition, not for security.</b> It decides whether two spellings name
 * the repository the operator already listed. What may be <em>fetched</em> is a separate and stricter
 * question, answered by {@link GitHubReleases#isAllowed} and re-checked at download time. Neither
 * replaces the other: a listed URL is still refused if it does not point at GitHub.
 */
public final class RepositoryUrls {

    private RepositoryUrls() {
    }

    /**
     * A repository URL reduced to the form two spellings of the same repository share.
     *
     * <p>Lowercased, trimmed, and stripped of a {@code .git} suffix and any trailing slashes — the
     * three ways the same GitHub repository is routinely written down. Nothing else is touched; this
     * deliberately does not parse the URL or canonicalise its host, because a URL it cannot make
     * sense of should fail to match rather than be coerced into matching something.
     *
     * <p>The suffix is one regex rather than the two sequential ones this grew from ({@code \.git$}
     * then {@code /+$}), which could not strip both at once: a trailing slash left {@code .git}
     * unanchored, so {@code …/widgets.git/} normalised to {@code …/widgets.git} and failed to match
     * the same repository written without either.
     *
     * @param url a repository URL, possibly null
     * @return the normalised form, or an empty string for null/blank input
     */
    public static String normalise(String url) {
        if (url == null) {
            return "";
        }
        return url.trim().toLowerCase(Locale.ROOT).replaceAll("/*(?:\\.git)?/*$", "");
    }

    /**
     * Whether {@code candidate} names the same repository as any entry in {@code allowed}.
     *
     * <p>A blank candidate never matches, even against a blank entry: an allowlist row that is empty
     * by accident must not become a wildcard.
     *
     * @param allowed   the accepted repository URLs
     * @param candidate the URL being checked
     * @return true when the candidate is on the list
     */
    public static boolean matches(Collection<String> allowed, String candidate) {
        String normalised = normalise(candidate);
        if (normalised.isEmpty() || allowed == null) {
            return false;
        }
        return allowed.stream().anyMatch(entry -> normalise(entry).equals(normalised));
    }
}
