/**
 * Discord integration.
 * <p>
 * {@link io.github.jaymcole.housegraph.discord.DiscordBot} wraps a JDA gateway
 * connection (the long-lived resource behind a Discord bot node);
 * {@link io.github.jaymcole.housegraph.discord.SlashCommandRegistry} lets command
 * nodes declare their commands independent of load order;
 * {@link io.github.jaymcole.housegraph.discord.CommandMatcher} matches text
 * triggers. The remaining types are the value/command shapes passed to Discord
 * nodes and handlers.
 * <p>
 * See {@code docs/architecture/integrations.md}.
 */
package io.github.jaymcole.housegraph.discord;
