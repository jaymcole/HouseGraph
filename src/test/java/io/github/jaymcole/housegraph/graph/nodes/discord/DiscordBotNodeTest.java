package io.github.jaymcole.housegraph.graph.nodes.discord;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Discord bot persists whether it was connected so it can auto-connect on load
 * (see {@code AutoStartable}). Stays on the headless persistence contract — the connected flag
 * round-tripping through {@code saveState}/{@code loadState} — since connecting talks to the
 * Discord gateway.
 */
class DiscordBotNodeTest {

    @Test
    void aDisconnectedBotWritesNoRunningFlag() {
        assertFalse(new DiscordBotNode().saveState().containsKey("running"),
                "a bot that isn't connected must not persist a running flag");
    }

    @Test
    void aRunningFlagInSavedStateSchedulesAutoConnect() {
        DiscordBotNode bot = new DiscordBotNode();
        assertFalse(bot.wasConnected(), "a fresh node has no pending auto-connect");

        bot.loadState(Map.of("name", "discord", "running", "true"));

        assertTrue(bot.wasConnected(), "a graph saved while connected reloads with auto-connect pending");
    }
}
