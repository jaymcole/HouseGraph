package io.github.jaymcole.housegraph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers the {@code run} argument translation.
 *
 * <p>Worth its own test because the failure it guards against is silent: JavaFX's
 * {@code Parameters.getNamed()} only recognises {@code --name=value}, so a graph passed the natural
 * way — {@code run porch.json} — would arrive as an unnamed argument, be ignored, and the app would
 * quietly open the last graph instead of the one asked for. That is exactly the command the
 * supervisor generates for every graph it starts.
 */
class LauncherTest {

    @Test
    void rewritesTheFirstBareArgumentIntoTheGraphParameter() {
        assertArrayEquals(new String[]{"--graph=porch.json"},
                Launcher.forApplication(new String[]{"run", "porch.json"}));
    }

    @Test
    void keepsTheOptionsThatFollow() {
        assertArrayEquals(new String[]{"--graph=/graphs/porch.json", "--minimized"},
                Launcher.forApplication(new String[]{"run", "/graphs/porch.json", "--minimized"}));
    }

    @Test
    void keepsOptionsThatComeFirst() {
        assertArrayEquals(new String[]{"--minimized", "--graph=porch.json"},
                Launcher.forApplication(new String[]{"run", "--minimized", "porch.json"}));
    }

    @Test
    void leavesAnExplicitGraphOptionExactlyAsWritten() {
        assertArrayEquals(new String[]{"--graph=porch.json", "--minimized"},
                Launcher.forApplication(new String[]{"run", "--graph=porch.json", "--minimized"}));
    }

    @Test
    void onlyTheFirstBareArgumentBecomesTheGraph() {
        // A second path is not a second graph — one process runs one graph — so it must not be
        // rewritten into a duplicate --graph that silently overrides the first.
        assertArrayEquals(new String[]{"--graph=one.json", "two.json"},
                Launcher.forApplication(new String[]{"run", "one.json", "two.json"}));
    }

    @Test
    void leavesAnythingThatIsNotARunAlone() {
        String[] editorArguments = {"--graph=porch.json"};
        assertSame(editorArguments, Launcher.forApplication(editorArguments));

        String[] none = {};
        assertSame(none, Launcher.forApplication(none));
    }
}
