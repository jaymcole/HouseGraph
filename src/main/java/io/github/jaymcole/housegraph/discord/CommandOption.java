package io.github.jaymcole.housegraph.discord;

/**
 * One declared slash-command option: its (lowercase) {@code name} and its {@link DiscordOptionType}.
 *
 * @param name the option's lowercase name (how its value is keyed in an invocation)
 * @param type the option's value type
 */
public record CommandOption(String name, DiscordOptionType type) {
}
