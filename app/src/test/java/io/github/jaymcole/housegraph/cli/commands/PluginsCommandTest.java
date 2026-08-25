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
 * Covers {@code plugins}'s argument handling only. The catalog shape itself is exercised
 * fixture-free by {@code PluginCatalogTest}, same reasoning as {@code NodesCommandTest}: {@code
 * list} reaches {@code PluginCatalog.load()}, which resolves against the {@code AppDirectories}
 * singleton and cannot be reliably isolated per test once another test has resolved it first.
 */
class PluginsCommandTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final CommandLine commandLine = new CommandLine(new PrintStream(captured, true, StandardCharsets.UTF_8));

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void helpMentionsTheJsonOption() {
        assertEquals(0, commandLine.run("plugins", "--help"));

        assertTrue(output().contains("plugins list [--json]"));
    }

    @Test
    void installWithNoUrlReportsUsageRatherThanTouchingTheCatalog() {
        assertNotEquals(0, commandLine.run("plugins", "install"));

        assertTrue(output().contains("Usage: housegraph plugins install"));
    }

    @Test
    void unknownActionIsReportedRatherThanAttemptingAnything() {
        int code = commandLine.run("plugins", "frobnicate");

        assertNotEquals(0, code);
        assertTrue(output().contains("Unknown plugins action: frobnicate"));
    }
}
