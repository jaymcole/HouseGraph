/**
 * An SLF4J provider that bridges third-party library logging (notably JDA's) into
 * HouseGraph's {@link io.github.jaymcole.housegraph.logging.LogManager}, so those messages
 * appear in the same console, log file, and log window as the app's own.
 * <p>
 * {@link io.github.jaymcole.housegraph.logging.slf4j.HouseGraphSlf4jProvider} is registered
 * via {@code META-INF/services/org.slf4j.spi.SLF4JServiceProvider} and hands out
 * {@link io.github.jaymcole.housegraph.logging.slf4j.HouseGraphSlf4jLogger}s that forward
 * into {@code LogManager}. {@link io.github.jaymcole.housegraph.logging.slf4j.Slf4jBridge}
 * holds the bridge's own minimum level (default {@code WARN}) and the SLF4J-to-{@code
 * LogLevel} mapping.
 * <p>
 * This adapter — unlike the dependency-free logging core — depends on the SLF4J API. It is
 * kept in its own subpackage so the core stays clean. See {@code docs/architecture/logging.md}.
 */
package io.github.jaymcole.housegraph.logging.slf4j;
