package io.github.jaymcole.housegraph.discord;

import java.util.Map;

/**
 * A received slash-command invocation, reduced to what the graph needs: which command,
 * the values the user supplied for each option (by lowercase option name), where and who
 * it came from, and a {@link DiscordReply} handle to answer the interaction. Published by
 * {@link DiscordBot} and consumed by the Discord slash-command node.
 *
 * @param command    the invoked command's name
 * @param options    the supplied option values, keyed by lowercase option name
 * @param channelId  the id of the channel it was run in
 * @param authorId   the invoking user's Discord id
 * @param authorName the invoking user's display name
 * @param reply      the handle used to answer the (deferred) interaction
 */
public record DiscordSlashCommand(String command, Map<String, String> options, String channelId, String authorId,
                                  String authorName, DiscordReply reply) {
}
