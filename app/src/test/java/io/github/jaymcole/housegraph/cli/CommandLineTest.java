package io.github.jaymcole.housegraph.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers dispatch and the exit codes it returns. A command returns its code rather than calling
 * {@code System.exit}, which is what lets this run in-process at all.
 */
class CommandLineTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final CommandLine commandLine = new CommandLine(new PrintStream(captured, true, StandardCharsets.UTF_8));

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void recognisesItsOwnCommands() {
        assertTrue(commandLine.handles("daemon"));
        assertTrue(commandLine.handles("doctor"));
        assertTrue(commandLine.handles("plugins"));
    }

    @Test
    void doesNotClaimRunOrAnythingItCannotHandle() {
        // `run` opens the editor, so it must fall through to Application.launch. If handles()
        // claimed it, launching a graph would silently do nothing instead of showing a window.
        assertFalse(commandLine.handles(CommandLine.RUN_COMMAND));
        assertFalse(commandLine.handles("--verbose"));
        assertFalse(commandLine.handles(null));
    }

    @Test
    void claimsHelpAndVersionEvenThoughTheyNameNoCommand() {
        // Otherwise `housegraph --help` falls through to Application.launch, which on a machine
        // with no display fails with a JavaFX stack trace that answers nothing.
        assertTrue(commandLine.handlesArguments("--help"));
        assertTrue(commandLine.handlesArguments("-h"));
        assertTrue(commandLine.handlesArguments("--version"));
        assertTrue(commandLine.handlesArguments("daemon"));
    }

    @Test
    void claimsAnyBareWordSoATypoIsReportedRatherThanOpeningAWindow() {
        // A bare first word is someone naming a command. Letting an unrecognised one through to
        // Application.launch would open an editor that silently ignored what they typed.
        assertTrue(commandLine.handlesArguments("frobnicate"));
        assertEquals(2, commandLine.run("frobnicate"));
        assertTrue(output().contains("Unknown command: frobnicate"));
    }

    @Test
    void leavesTheEditorsArgumentsAlone() {
        assertFalse(commandLine.handlesArguments(), "no arguments means open the editor");
        assertFalse(commandLine.handlesArguments(CommandLine.RUN_COMMAND, "porch.json"),
                "run IS the editor");
        assertFalse(commandLine.handlesArguments("--graph=porch.json"));
        assertFalse(commandLine.handlesArguments("--verbose"));
    }

    @Test
    void versionPrintsAVersionRatherThanUsage() {
        assertEquals(0, commandLine.run("--version"));

        assertTrue(output().startsWith(CommandLine.PROGRAM + " "));
        assertFalse(output().contains("Commands:"));
    }

    @Test
    void noArgumentsPrintsTheUsageAndSucceeds() {
        assertEquals(0, commandLine.run());

        assertTrue(output().contains("Usage: housegraph"));
        assertTrue(output().contains(CommandLine.RUN_COMMAND), "run belongs in the listing even "
                + "though it isn't dispatched here — it's where someone will look for it");
    }

    @Test
    void anUnknownCommandFailsAndSaysSo() {
        int code = commandLine.run("frobnicate");

        assertNotEquals(0, code);
        assertTrue(output().contains("Unknown command: frobnicate"));
    }

    @Test
    void helpOnACommandPrintsThatCommandsUsage() {
        assertEquals(0, commandLine.run("plugins", "--help"));

        assertTrue(output().contains("plugins install"));
    }

    @Test
    void homeIsAppliedBeforeACommandRuns() {
        // AppDirectories caches its root on first use, so --home has to land as a system property
        // before any command touches it. Setting it in dispatch means no command can forget.
        String previous = System.getProperty("housegraph.home");
        try {
            commandLine.run("check", "--home=/tmp/housegraph-test-home");

            assertEquals("/tmp/housegraph-test-home", System.getProperty("housegraph.home"));
        } finally {
            if (previous == null) {
                System.clearProperty("housegraph.home");
            } else {
                System.setProperty("housegraph.home", previous);
            }
        }
    }

    @Test
    void checkWithNoFileReportsUsageRatherThanThrowing() {
        assertNotEquals(0, commandLine.run("check"));

        assertTrue(output().contains("housegraph check"));
    }
}
