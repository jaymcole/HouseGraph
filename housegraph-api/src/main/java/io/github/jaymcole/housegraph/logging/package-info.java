/**
 * The application's logging system: a small, dependency-free facade over a set of
 * pluggable, independently-filtered outputs.
 * <p>
 * Code emits messages through a {@link io.github.jaymcole.housegraph.logging.Logger}
 * obtained from {@link io.github.jaymcole.housegraph.logging.Log}. Every message flows to
 * {@link io.github.jaymcole.housegraph.logging.LogManager}, which fans it out to the
 * registered {@link io.github.jaymcole.housegraph.logging.LogSink}s whose per-output
 * {@link io.github.jaymcole.housegraph.logging.LogLevel} it clears — so the same call can be
 * verbose in one place and silent in another.
 * <p>
 * Three outputs ship: a {@link io.github.jaymcole.housegraph.logging.ConsoleSink}, a
 * {@link io.github.jaymcole.housegraph.logging.FileSink}, and a
 * {@link io.github.jaymcole.housegraph.logging.LogBufferSink} — a bounded in-memory ring
 * that keeps capturing whether or not the log window is open, which is what lets that window
 * be closed and reopened without losing history.
 * {@link io.github.jaymcole.housegraph.logging.Logging} wires them up at startup.
 * <p>
 * This package imports nothing from the rest of the app (and never JavaFX), so any layer may
 * depend on it without creating a cycle. See {@code docs/architecture/logging.md}.
 */
package io.github.jaymcole.housegraph.logging;
