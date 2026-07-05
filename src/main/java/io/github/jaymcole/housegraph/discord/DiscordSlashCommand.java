package io.github.jaymcole.housegraph.discord;

import java.util.Map;

/**
 * A received slash-command invocation, reduced to what the graph needs: which command,
 * the values the user supplied for each option (by lowercase option name), where and who
 * it came from, and a {@link DiscordReply} handle to answer the interaction. Published by
 * {@link DiscordBot} and consumed by the Discord slash-command node.
 */
public record DiscordSlashCommand(String command, Map<String, String> options, String channelId, String authorId,
                                  String authorName, DiscordReply reply) {
}
