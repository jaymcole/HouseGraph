package io.github.jaymcole.housegraph.discord;

import java.util.List;

/**
 * How a slash command should be registered and answered: its {@code name},
 * {@code description}, {@code options}, and whether its reply is {@code ephemeral}
 * (visible only to the person who ran it). Ephemeral has to be known when the interaction
 * is <em>deferred</em> — before the graph runs — so it travels with the command's
 * registration rather than being decided at reply time.
 *
 * @param name        the command name Discord registers it under
 * @param description the command's description, shown in Discord's command picker
 * @param ephemeral   whether the reply is visible only to the person who ran the command
 * @param options     the options (arguments) the command declares
 */
public record SlashCommandSpec(String name, String description, boolean ephemeral, List<CommandOption> options) {
}
