package io.github.jaymcole.housegraph.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryUrlsTest {

    private static final List<String> TRUSTED = List.of("https://github.com/example/housegraph-widgets");

    @Test
    void theThreeSpellingsOfTheSameRepositoryAllMatch() {
        // How GitHub displays it, how git clones it, and how a copy-paste leaves it.
        assertTrue(RepositoryUrls.matches(TRUSTED, "https://github.com/example/housegraph-widgets"));
        assertTrue(RepositoryUrls.matches(TRUSTED, "https://github.com/example/housegraph-widgets.git"));
        assertTrue(RepositoryUrls.matches(TRUSTED, "https://github.com/example/housegraph-widgets/"));
        assertTrue(RepositoryUrls.matches(TRUSTED, "  https://GitHub.com/Example/HouseGraph-Widgets  "));
    }

    @Test
    void aDifferentRepositoryDoesNotMatch() {
        assertFalse(RepositoryUrls.matches(TRUSTED, "https://github.com/someone-else/housegraph-widgets"));
        assertFalse(RepositoryUrls.matches(TRUSTED, "https://github.com/example/housegraph-widgets-evil"));
    }

    @Test
    void blankAndNullNeverMatch() {
        // A blank entry left in the list by accident must not become a wildcard.
        assertFalse(RepositoryUrls.matches(List.of(""), ""));
        assertFalse(RepositoryUrls.matches(List.of(""), null));
        assertFalse(RepositoryUrls.matches(TRUSTED, null));
        assertFalse(RepositoryUrls.matches(null, "https://github.com/example/housegraph-widgets"));
    }

    @Test
    void normaliseStripsOnlyWhatTwoSpellingsShare() {
        assertEquals("https://github.com/example/widgets",
                RepositoryUrls.normalise("HTTPS://GitHub.com/Example/Widgets.git///"));
        assertEquals("", RepositoryUrls.normalise(null));
        // Not a URL parser: anything it can't make sense of is left to fail the match rather than
        // being coerced into one.
        assertEquals("not a url", RepositoryUrls.normalise("  Not A URL  "));
    }
}
