package io.github.jaymcole.housegraph.discord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where slash-command nodes <em>declare</em> the commands they provide, keyed by bot
 * name, so registration doesn't depend on node load order: a command node declares its
 * command whenever it likes, and the bot reads the full set for its name when it
 * connects (and re-registers). Declaring/withdrawing here does not itself talk to
 * Discord — the bot node syncs the declared set to Discord on Connect.
 * <p>
 * The natural rule that follows: set up your command nodes, then connect the bot;
 * changing a command (name, ephemeral flag, …) afterward means a reconnect to apply it.
 */
public final class SlashCommandRegistry {

    private static final SlashCommandRegistry SHARED = new SlashCommandRegistry();

    /** botName -> (commandName -> spec). */
    private final Map<String, Map<String, SlashCommandSpec>> byBot = new ConcurrentHashMap<>();

    public static SlashCommandRegistry shared() {
        return SHARED;
    }

    public void declare(String botName, SlashCommandSpec spec) {
        byBot.computeIfAbsent(botName, key -> new ConcurrentHashMap<>()).put(spec.name(), spec);
    }

    public void withdraw(String botName, String command) {
        Map<String, SlashCommandSpec> commands = byBot.get(botName);
        if (commands != null) {
            commands.remove(command);
        }
    }

    /** A snapshot of the specs declared for {@code botName}. */
    public Collection<SlashCommandSpec> commandsFor(String botName) {
        List<SlashCommandSpec> specs = new ArrayList<>(byBot.getOrDefault(botName, Map.of()).values());
        specs.sort((a, b) -> a.name().compareTo(b.name()));
        return specs;
    }
}
