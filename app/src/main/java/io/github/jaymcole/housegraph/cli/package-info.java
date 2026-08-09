/**
 * The command line: everything HouseGraph can do without a window.
 *
 * <p>{@code Launcher} asks {@link io.github.jaymcole.housegraph.cli.CommandLine#handles} whether the
 * first argument names a command here. If it does, the command runs and the JVM exits with its
 * return value; if it doesn't — including when there are no arguments at all — JavaFX starts as it
 * always has. One jar, one {@code Main-Class}, and the bare {@code java -jar app.jar} behaviour
 * unchanged.
 *
 * <p>{@link io.github.jaymcole.housegraph.cli.Args} is a pure parser and
 * {@link io.github.jaymcole.housegraph.cli.Command} returns an exit code rather than calling
 * {@code System.exit}, so a command is an ordinary function a test can call and assert on. The
 * commands themselves live in {@code cli.commands}.
 *
 * <p>Note that {@code run} is not in the command table: opening a graph <em>is</em> the GUI, so it
 * falls through to the normal application launch and is handled by {@code App}'s {@code --graph}
 * argument. It appears in the usage text because that is where someone will look for it.
 *
 * <p>Full design: {@code docs/architecture/deployment.md}.
 */
package io.github.jaymcole.housegraph.cli;
