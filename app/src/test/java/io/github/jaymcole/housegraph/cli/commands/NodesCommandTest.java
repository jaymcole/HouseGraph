package io.github.jaymcole.housegraph.cli.commands;

import io.github.jaymcole.housegraph.cli.CommandLine;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code nodes}'s argument handling only. The catalog-building and drift-checking logic
 * itself is exercised directly, fixture-free, by {@code NodeCatalogTest} and
 * {@code SchemaDriftCheckTest} — this command is a thin front end over both, same as
 * {@code CheckCommand} is over {@code GraphDependencyCheck}.
 *
 * <p>Deliberately does not exercise {@code list} or a resolvable {@code check} here: both reach
 * {@code PluginCatalog.load()}, which resolves against {@code AppDirectories.get()} — a singleton
 * cached for the life of the JVM on first use (see {@code AppDirectories}), so a per-test
 * {@code --home} cannot reliably isolate it once another test in the same run has already resolved
 * it. Every other test in this codebase that needs an isolated catalog uses
 * {@code PluginCatalog.loadFrom} directly for exactly this reason.
 */
class NodesCommandTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final CommandLine commandLine = new CommandLine(new PrintStream(captured, true, StandardCharsets.UTF_8));

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void checkWithNoFileReportsUsageRatherThanTouchingTheCatalog() {
        assertNotEquals(0, commandLine.run("nodes", "check"));

        assertTrue(output().contains("Usage: housegraph nodes check"));
    }

    @Test
    void checkWithAMissingFileFailsCleanly() {
        assertNotEquals(0, commandLine.run("nodes", "check", "/no/such/graph.json"));

        assertTrue(output().contains("No such file"));
    }

    @Test
    void unknownActionIsReportedRatherThanAttemptingAnything() {
        int code = commandLine.run("nodes", "frobnicate");

        assertNotEquals(0, code);
        assertTrue(output().contains("Unknown nodes action: frobnicate"));
    }

    @Test
    void helpPrintsBothActions() {
        assertEquals(0, commandLine.run("nodes", "--help"));

        assertTrue(output().contains("nodes list"));
        assertTrue(output().contains("nodes check"));
    }
}
