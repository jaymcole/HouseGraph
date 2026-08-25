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
 * Covers {@code validate}'s argument handling only. The structural-analysis logic itself is
 * exercised directly, fixture-free, by {@code GraphStructureValidatorTest} — this command is a thin
 * front end over it, same as {@code CheckCommand} is over {@code GraphDependencyCheck}.
 *
 * <p>Deliberately does not exercise a resolvable file here: that reaches {@code PluginCatalog.load()},
 * which resolves against the {@code AppDirectories} singleton — see {@code NodesCommandTest} for why
 * that can't be reliably isolated per test in this suite.
 */
class ValidateCommandTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final CommandLine commandLine = new CommandLine(new PrintStream(captured, true, StandardCharsets.UTF_8));

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void withNoFileReportsUsageRatherThanTouchingTheCatalog() {
        assertNotEquals(0, commandLine.run("validate"));

        assertTrue(output().contains("Usage: housegraph validate"));
    }

    @Test
    void withAMissingFileFailsCleanly() {
        assertNotEquals(0, commandLine.run("validate", "/no/such/graph.json"));

        assertTrue(output().contains("No such file"));
    }

    @Test
    void helpDescribesTheCommand() {
        assertEquals(0, commandLine.run("validate", "--help"));

        assertTrue(output().contains("dangling"));
        assertTrue(output().contains("JSON Pointer"));
    }
}
