package io.github.jaymcole.housegraph.discord;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>slash commands are registered via {@link #syncCommands} and their invocations
 *       forwarded to {@link #setSlashHandler a handler}, deferred so a slow graph has
 *       time (~15 min) to answer through the {@link DiscordReply} handle.</li>
 *   <li>{@link #sendMessage} posts to a channel by id.</li>
 * </ul>
 * Reading message content needs the privileged <b>MESSAGE_CONTENT</b> intent enabled for
 * the bot in Discord's developer portal; slash commands need no special intent.
 */
public final class DiscordBot {

    private static final Logger log = Log.get(DiscordBot.class);

    private final Object lock = new Object();
    private JDA jda;
    private volatile String guildId;
    /** Command name (lowercase) -> whether its reply is ephemeral; consulted when deferring an interaction. */
    private final Map<String, Boolean> ephemeralByCommand = new ConcurrentHashMap<>();
    private volatile Consumer<DiscordMessage> messageHandler = message -> {
    };
    private volatile Consumer<DiscordSlashCommand> slashHandler = command -> {
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

    /** Sets where incoming (non-bot) messages are delivered. Called back on a JDA thread. */
    public void setMessageHandler(Consumer<DiscordMessage> handler) {
        this.messageHandler = handler == null ? message -> {
        } : handler;
    }

    /** Sets where slash-command invocations are delivered (already deferred). Called back on a JDA thread. */
    public void setSlashHandler(Consumer<DiscordSlashCommand> handler) {
        this.slashHandler = handler == null ? command -> {
        } : handler;
    }

    /** The guild (server) id to register slash commands to for instant availability; null/blank registers globally (slow to propagate). */
    public void setGuildId(String guildId) {
        this.guildId = guildId == null || guildId.isBlank() ? null : guildId;
    }

    /**
     * Registers exactly {@code specs} as this bot's slash commands, each with one
     * optional text argument, and remembers their ephemeral flags for deferring. Replaces
     * the previous set. Registers to the configured {@link #setGuildId guild} if set
     * (instant), otherwise globally (~1 hour to propagate). A no-op if not connected.
     */
    public void syncCommands(Collection<SlashCommandSpec> specs) {
        JDA current;
        String guild;
        synchronized (lock) {
            current = jda;
            guild = guildId;
        }
        if (current == null) {
            return;
        }
        ephemeralByCommand.clear();
        List<SlashCommandData> data = new ArrayList<>();
        for (SlashCommandSpec spec : specs) {
            String name = spec.name().toLowerCase(Locale.ROOT);
            try {
                SlashCommandData command = Commands.slash(name, spec.description());
                for (CommandOption option : spec.options()) {
                    command.addOption(toJdaType(option.type()), option.name().toLowerCase(Locale.ROOT), option.name(), false);
                }
                data.add(command);
                ephemeralByCommand.put(name, spec.ephemeral());
            } catch (IllegalArgumentException e) {
                // Discord requires lowercase names of letters/digits/-/_ ; skip a bad one
                // rather than failing registration of every command.
                log.warn("Skipping invalid slash command '{}': {}", name, e.getMessage());
            }
        }
        Guild target = guild == null ? null : current.getGuildById(guild);
        if (target != null) {
            target.updateCommands().addCommands(data).queue();
        } else {
            current.updateCommands().addCommands(data).queue();
        }
    }

    /** Posts {@code text} to the message channel with the given id; a no-op if not connected or the channel isn't found. */
    public void sendMessage(String channelId, String text) {
        JDA current;
        synchronized (lock) {
            current = jda;
        }
        if (current == null) {
            return;
        }
        MessageChannel channel = current.getChannelById(MessageChannel.class, channelId);
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
            messageHandler.accept(new DiscordMessage(
                    event.getMessage().getContentDisplay(),
                    event.getChannel().getId(),
                    event.getAuthor().getId(),
                    event.getAuthor().getEffectiveName()));
        }

        @Override
        public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
            // Acknowledge within Discord's 3s window; the real answer is sent later
            // through the hook (valid ~15 min), so a slow graph still gets to reply.
            // Ephemeral (invoker-only) is decided here, at defer time.
            boolean ephemeral = ephemeralByCommand.getOrDefault(event.getName(), false);
            event.deferReply(ephemeral).queue();
            InteractionHook hook = event.getHook();
            DiscordReply reply = text -> hook.editOriginal(text).queue();

            Map<String, String> options = new java.util.HashMap<>();
            for (OptionMapping option : event.getOptions()) {
                options.put(option.getName(), option.getAsString());
            }
            slashHandler.accept(new DiscordSlashCommand(
                    event.getName(),
                    options,
                    event.getChannel().getId(),
                    event.getUser().getId(),
                    event.getUser().getEffectiveName(),
                    reply));
        }
    }

    private static OptionType toJdaType(DiscordOptionType type) {
        return switch (type) {
            case TEXT -> OptionType.STRING;
            case INTEGER -> OptionType.INTEGER;
            case BOOLEAN -> OptionType.BOOLEAN;
            case USER -> OptionType.USER;
        };
    }
}
