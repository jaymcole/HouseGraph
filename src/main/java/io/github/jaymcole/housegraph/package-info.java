/**
 * Application entry points and top-level wiring.
 * <p>
 * {@link io.github.jaymcole.housegraph.Launcher} is the {@code main} that is
 * actually run; it delegates to {@link io.github.jaymcole.housegraph.App}, the
 * JavaFX {@code Application} that builds the graph, canvas, and toolbar. The split
 * lets JavaFX launch cleanly from a plain classpath jar.
 * <p>
 * See the repository {@code CLAUDE.md} and {@code docs/architecture/} for the full
 * architecture; start with {@code docs/architecture/overview.md}.
 */
package io.github.jaymcole.housegraph;
