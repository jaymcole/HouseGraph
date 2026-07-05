package io.github.jaymcole.housegraph.discord;

/**
 * The kinds of slash-command option HouseGraph exposes. The type controls the input UI
 * Discord shows (a number field, a user picker, …); the graph always receives the value
 * as text. Mapped to JDA's option types by {@link DiscordBot}.
 */
public enum DiscordOptionType {
    TEXT,
    INTEGER,
    BOOLEAN,
    USER
}
