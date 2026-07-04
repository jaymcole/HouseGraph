package io.github.jaymcole.housegraph.discord;

/**
 * A received Discord message reduced to what the graph cares about: the text, the
 * channel it came from (so a command can reply there), and who sent it. Published by
 * {@link DiscordBot} and consumed by the Discord command node.
 */
public record DiscordMessage(String content, String channelId, String authorId, String authorName) {
}
