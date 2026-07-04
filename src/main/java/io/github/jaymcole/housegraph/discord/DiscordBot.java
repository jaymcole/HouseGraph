package io.github.jaymcole.housegraph.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.EnumSet;
import java.util.function.Consumer;

/**
 * A thin wrapper around a JDA gateway connection — the long-lived resource behind a
 * Discord bot node. JDA keeps the connection alive (heartbeats, reconnects) as long as
 * the instance is held; this class just manages hold/release and adapts JDA's async,
 * multi-threaded world to the simple surface the rest of the app needs:
 * <ul>
 *   <li>{@link #connect} logs in and blocks until the gateway is ready (call it off the
 *       UI thread); {@link #disconnect} shuts it down.</li>
 *   <li>incoming (non-bot) messages are forwarded to {@link #setMessageHandler a handler}
 *       — the app routes those into the resource registry as events.</li>
 *   <li>{@link #sendMessage} posts to a channel by id.</li>
 * </ul>
 * Reading message content needs the privileged <b>MESSAGE_CONTENT</b> intent enabled for
 * the bot in Discord's developer portal.
 */
public final class DiscordBot {

    private final Object lock = new Object();
    private JDA jda;
    private volatile Consumer<String> messageHandler = message -> {
    };

    /**
     * Logs in with {@code token} and blocks until the gateway is ready. Call from a
     * background thread — it waits on the network.
     *
     * @throws InterruptedException if interrupted while awaiting readiness
     * @throws RuntimeException     if the token is invalid or login otherwise fails
     */
    public void connect(String token) throws InterruptedException {
        JDA built = JDABuilder.createLight(token, EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
                .addEventListeners(new MessageBridge())
                .build()
                .awaitReady();
        synchronized (lock) {
            this.jda = built;
        }
    }

    public void disconnect() {
        JDA current;
        synchronized (lock) {
            current = jda;
            jda = null;
        }
        if (current != null) {
            current.shutdownNow();
        }
    }

    public boolean isConnected() {
        synchronized (lock) {
            return jda != null && jda.getStatus() == JDA.Status.CONNECTED;
        }
    }

    /** Sets where incoming (non-bot) message content is delivered. Called back on a JDA thread. */
    public void setMessageHandler(Consumer<String> handler) {
        this.messageHandler = handler == null ? message -> {
        } : handler;
    }

    /** Posts {@code text} to the text channel with the given id; a no-op if not connected or the channel isn't found. */
    public void sendMessage(String channelId, String text) {
        JDA current;
        synchronized (lock) {
            current = jda;
        }
        if (current == null) {
            return;
        }
        TextChannel channel = current.getTextChannelById(channelId);
        if (channel != null) {
            channel.sendMessage(text).queue();
        }
    }

    private final class MessageBridge extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (event.getAuthor().isBot()) {
                return; // ignore our own and other bots' messages
            }
            messageHandler.accept(event.getMessage().getContentDisplay());
        }
    }
}
