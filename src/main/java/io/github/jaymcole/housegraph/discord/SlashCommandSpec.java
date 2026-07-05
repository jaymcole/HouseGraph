package io.github.jaymcole.housegraph.discord;

import java.util.List;

/**
 * How a slash command should be registered and answered: its {@code name},
 * {@code description}, {@code options}, and whether its reply is {@code ephemeral}
 * (visible only to the person who ran it). Ephemeral has to be known when the interaction
 * is <em>deferred</em> — before the graph runs — so it travels with the command's
 * registration rather than being decided at reply time.
 */
public record SlashCommandSpec(String name, String description, boolean ephemeral, List<CommandOption> options) {
}
