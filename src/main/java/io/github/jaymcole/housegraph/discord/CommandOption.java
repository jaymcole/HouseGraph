package io.github.jaymcole.housegraph.discord;

/** One declared slash-command option: its (lowercase) {@code name} and its {@link DiscordOptionType}. */
public record CommandOption(String name, DiscordOptionType type) {
}
