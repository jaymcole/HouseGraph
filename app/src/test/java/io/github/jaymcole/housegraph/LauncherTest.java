package io.github.jaymcole.housegraph;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@code run} argument translation, and the fork between the window and the headless
 * runner that reads the same arguments.
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
        assertArrayEquals(new String[]{"--graph=/graphs/porch.json", "--verbose"},
                Launcher.forApplication(new String[]{"run", "/graphs/porch.json", "--verbose"}));
    }

    @Test
    void keepsOptionsThatComeFirst() {
        assertArrayEquals(new String[]{"--verbose", "--graph=porch.json"},
                Launcher.forApplication(new String[]{"run", "--verbose", "porch.json"}));
    }

    @Test
    void leavesAnExplicitGraphOptionExactlyAsWritten() {
        assertArrayEquals(new String[]{"--graph=porch.json", "--verbose"},
                Launcher.forApplication(new String[]{"run", "--graph=porch.json", "--verbose"}));
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

    // --- The headless fork ---------------------------------------------------------

    @Test
    void runWithoutTheFlagIsStillTheEditor() {
        // The whole point of making it opt-in: someone who types `housegraph run porch.json` at a
        // desktop gets a window, exactly as they always have.
        assertFalse(Launcher.isHeadlessRun("run", "porch.json"));
        assertFalse(Launcher.isHeadlessRun("run"));
        assertFalse(Launcher.isHeadlessRun("--graph=porch.json"));
        assertFalse(Launcher.isHeadlessRun());
    }

    @Test
    void runWithTheFlagIsTheHeadlessRunner() {
        assertTrue(Launcher.isHeadlessRun("run", "--headless", "porch.json"));
        assertTrue(Launcher.isHeadlessRun("run", "porch.json", "--headless"),
                "the flag is a flag, so where it is written must not change what it means");
        assertTrue(Launcher.isHeadlessRun("run", "--headless=true", "porch.json"));
    }

    @Test
    void theFlagOnlyMeansSomethingOnRun() {
        // `daemon --headless` is not a headless graph run; it is a command the table handles, and
        // Launcher has already dispatched it by the time this is asked.
        assertFalse(Launcher.isHeadlessRun("daemon", "--headless"));
    }

    @Test
    void theHeadlessRunTakesItsGraphFromTheSamePlaceTheWindowDoes() {
        // Args.parse would read `--headless porch.json` as a flag with a value and swallow the
        // graph; the first bare argument is the graph on both paths, however the flag is written.
        assertEquals(Optional.of("porch.json"), Launcher.graphArgument("run", "--headless", "porch.json"));
        assertEquals(Optional.of("porch.json"), Launcher.graphArgument("run", "porch.json", "--headless"));
        assertEquals(Optional.of("/graphs/porch.json"),
                Launcher.graphArgument("run", "--graph=/graphs/porch.json", "--headless"));
        assertEquals(Optional.empty(), Launcher.graphArgument("run", "--headless"),
                "no graph named at all is a configuration error, not a reason to guess at the last one");
    }
}
