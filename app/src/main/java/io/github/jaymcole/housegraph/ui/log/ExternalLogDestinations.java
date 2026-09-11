package io.github.jaymcole.housegraph.ui.log;

import io.github.jaymcole.housegraph.logging.DiscordWebhookSink;
import io.github.jaymcole.housegraph.logging.LogLevel;
import io.github.jaymcole.housegraph.logging.LogManager;
import io.github.jaymcole.housegraph.storage.AppPreferences;
import io.github.jaymcole.housegraph.storage.SecretsStore;

import java.util.Optional;

/**
 * Owns the log outputs that send somewhere off this machine, and everything needed to
 * remember one across launches. Today that is a single destination — a Discord webhook —
 * but the split it establishes is the point: <em>what</em> a destination does lives in a
 * {@link io.github.jaymcole.housegraph.logging.LogSink}, and <em>whether and how</em> it is
 * configured lives here.
 *
 * <h2>Where the settings are kept</h2>
 * A webhook URL is a credential: anyone holding it can post to the channel. So it is stored
 * in the encrypted {@link SecretsStore} under {@value #WEBHOOK_SECRET}, never in
 * {@code preferences.json}. What is left is not sensitive and lives in
 * {@link AppPreferences}: {@value #ENABLED_KEY} for the on/off switch, and the destination's
 * level under the same {@code log.level.<sink>} key every other output uses, so the log
 * window's per-output dropdown drives it like any other.
 *
 * <p>Enabled-but-unconfigured is not a state: the destination is registered only when it is
 * switched on <em>and</em> the store holds a usable webhook URL. A secret deleted behind the
 * app's back therefore turns the destination off rather than producing a sink that fails
 * every post.
 *
 * <h2>Why this lives in the UI layer</h2>
 * The same reason {@link LogLevelPreferences} does: {@code logging} stays dependency-free
 * and must not import {@code storage}, so the layer that already knows about both ties them
 * together. {@code App} applies the saved settings at startup and closes the destination on
 * the way out; the log window's settings dialog is the only other caller.
 */
public final class ExternalLogDestinations {

    /** {@link SecretsStore} key holding the Discord webhook URL. */
    public static final String WEBHOOK_SECRET = "log.discord.webhook";

    /** {@link AppPreferences} key for whether the Discord destination is switched on. */
    static final String ENABLED_KEY = "log.discord.enabled";

    /** Used when nothing is saved yet: loud enough to be worth a notification, quiet enough to stay quiet. */
    static final LogLevel DEFAULT_LEVEL = LogLevel.WARN;

    /** The registered destination, or null when it is switched off. */
    private static DiscordWebhookSink discord;

    private ExternalLogDestinations() {
    }

    /**
     * The Discord destination as the user configured it.
     *
     * @param enabled    whether records should be forwarded
     * @param webhookUrl the webhook URL, or {@code ""} when none has been entered
     * @param level      the minimum level forwarded
     */
    public record DiscordSettings(boolean enabled, String webhookUrl, LogLevel level) {

        public DiscordSettings {
            webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
            level = level == null ? DEFAULT_LEVEL : level;
        }

        /** Whether these settings describe a destination that can actually be stood up. */
        public boolean usable() {
            return enabled && DiscordWebhookSink.isWebhookUrl(webhookUrl);
        }
    }

    /**
     * Reads the saved Discord settings. The webhook URL comes from the encrypted store, so an
     * unopenable store yields settings with no URL rather than throwing — a broken secrets
     * file must not stop the app from starting.
     *
     * @param preferences the shared preferences store
     * @return the saved settings (switched off, with no URL, when nothing is saved)
     */
    public static DiscordSettings discordSettings(AppPreferences preferences) {
        boolean enabled = preferences.get(ENABLED_KEY).map(Boolean::parseBoolean).orElse(false);
        LogLevel level = LogLevelPreferences
                .savedLevel(preferences, DiscordWebhookSink.SINK_NAME)
                .orElse(DEFAULT_LEVEL);
        return new DiscordSettings(enabled, readWebhook().orElse(""), level);
    }

    /**
     * Saves {@code settings} and brings the live destination into line with them: registering
     * it, re-registering it against a changed URL, or removing it. Safe to call repeatedly.
     *
     * @param preferences the shared preferences store
     * @param settings    what the user chose
     */
    public static synchronized void applyDiscord(AppPreferences preferences, DiscordSettings settings) {
        writeWebhook(settings.webhookUrl());
        preferences.put(ENABLED_KEY, Boolean.toString(settings.enabled()));
        preferences.save();
        // Through LogLevelPreferences rather than a second copy of the key format, and by name
        // because a destination being switched off has no registered sink to read it back from.
        LogLevelPreferences.persist(preferences, DiscordWebhookSink.SINK_NAME, settings.level());
        install(settings);
    }

    /**
     * Stands the destination up from what was saved. Call once at startup, before
     * {@link LogLevelPreferences#restore} so the new sink is among the ones it reaches.
     *
     * @param preferences the shared preferences store
     */
    public static synchronized void restore(AppPreferences preferences) {
        install(discordSettings(preferences));
    }

    /**
     * Flushes and closes any external destination. Called from {@code App.stop}, alongside
     * {@link io.github.jaymcole.housegraph.logging.Logging#shutdown()}, so the last queued
     * records are delivered before the process exits.
     */
    public static synchronized void shutdown() {
        removeDiscord();
    }

    /** Replaces whatever is registered with what {@code settings} describes. */
    private static void install(DiscordSettings settings) {
        if (!settings.usable()) {
            removeDiscord();
            return;
        }
        if (discord != null && discord.webhookUrl().equals(settings.webhookUrl())) {
            // Same destination, possibly a new level: keep the worker and its queue alive rather
            // than tearing down a sink that is mid-delivery.
            discord.setLevel(settings.level());
            return;
        }
        removeDiscord();
        try {
            discord = new DiscordWebhookSink(settings.webhookUrl(), settings.level());
            LogManager.get().addSink(discord);
        } catch (RuntimeException e) {
            // A URL that passed validation on the way in can still be rejected here (a store
            // edited by hand). Stay off rather than half-configured.
            discord = null;
            System.err.println("Discord log destination disabled: " + e);
        }
    }

    private static void removeDiscord() {
        if (discord == null) {
            return;
        }
        LogManager.get().removeSink(discord);
        discord.close();
        discord = null;
    }

    private static Optional<String> readWebhook() {
        try {
            SecretsStore store = SecretsStore.open();
            return Optional.ofNullable(store.get(WEBHOOK_SECRET));
        } catch (RuntimeException e) {
            System.err.println("Could not read the Discord log webhook from the secret store: " + e);
            return Optional.empty();
        }
    }

    private static void writeWebhook(String webhookUrl) {
        try {
            SecretsStore store = SecretsStore.open();
            if (webhookUrl.isEmpty()) {
                store.remove(WEBHOOK_SECRET);
            } else {
                store.put(WEBHOOK_SECRET, webhookUrl);
            }
            store.save();
        } catch (RuntimeException e) {
            System.err.println("Could not save the Discord log webhook to the secret store: " + e);
        }
    }
}
