package io.github.jaymcole.housegraph.ui.log;

import io.github.jaymcole.housegraph.logging.LogLevel;
import io.github.jaymcole.housegraph.ui.log.ExternalLogDestinations.DiscordSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the rule that decides whether an external destination is stood up at all. The rest
 * of {@link ExternalLogDestinations} reads and writes the machine's real preference and
 * secret stores, so it is left to the app; nothing here touches either.
 */
class ExternalLogDestinationsTest {

    private static final String URL = "https://discord.com/api/webhooks/123456/abcdef";

    @Test
    void onWithAWebhookIsTheOnlyUsableCombination() {
        assertTrue(new DiscordSettings(true, URL, LogLevel.WARN).usable());
        assertFalse(new DiscordSettings(false, URL, LogLevel.WARN).usable(), "switched off");
        assertFalse(new DiscordSettings(true, "", LogLevel.WARN).usable(), "nothing configured");
        assertFalse(new DiscordSettings(true, "https://example.com/hook", LogLevel.WARN).usable(),
                "not a Discord webhook");
    }

    @Test
    void aMissingUrlOrLevelBecomesTheDefaultRatherThanNull() {
        DiscordSettings settings = new DiscordSettings(false, null, null);

        assertEquals("", settings.webhookUrl());
        assertEquals(ExternalLogDestinations.DEFAULT_LEVEL, settings.level());
    }

    @Test
    void aPastedUrlIsTrimmed() {
        assertTrue(new DiscordSettings(true, "  " + URL + "  ", LogLevel.WARN).usable());
        assertEquals(URL, new DiscordSettings(true, "  " + URL + "  ", LogLevel.WARN).webhookUrl());
    }
}
