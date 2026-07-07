package io.github.jaymcole.housegraph.discord;

/**
 * A received Discord message reduced to what the graph cares about: the text, the
 * channel it came from (so a command can reply there), and who sent it. Published by
 * {@link DiscordBot} and consumed by the Discord command node.
 *
 * @param content    the message text
 * @param channelId  the id of the channel it was posted in (reply target)
 * @param authorId   the sender's Discord user id
 * @param authorName the sender's display name
 */
public record DiscordMessage(String content, String channelId, String authorId, String authorName) {
}
