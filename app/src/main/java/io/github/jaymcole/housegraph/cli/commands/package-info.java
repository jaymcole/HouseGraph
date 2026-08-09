/**
 * The individual subcommands, one class each.
 *
 * <p>Each is a thin front end over machinery that was already headless — {@code plugin} for
 * installing libraries and checking a graph's dependencies, {@code remote} for git and supervision.
 * That is deliberate: a command here should read as an arrangement of existing calls plus the words
 * a person needs to see, not as a second implementation of anything.
 *
 * <p>Registered in {@link io.github.jaymcole.housegraph.cli.CommandLine}'s constructor; that table
 * is the only place a command has to be named.
 */
package io.github.jaymcole.housegraph.cli.commands;
