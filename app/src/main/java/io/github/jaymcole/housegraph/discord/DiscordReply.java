package io.github.jaymcole.housegraph.discord;

/**
 * A one-shot handle for replying to a specific slash-command invocation. Backed by
 * JDA's deferred interaction hook, so it's valid for about 15 minutes after the command
 * was run — long enough for a slow graph to finish and answer. Flows through the graph
 * as a value from the command node to a reply node.
 */
@FunctionalInterface
public interface DiscordReply {

    void reply(String text);
}
