package io.github.jaymcole.housegraph.cli;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the hand-rolled argument parser. Pure, so this is the whole of its behaviour — there is
 * no CLI framework underneath doing anything these assertions can't see.
 */
class ArgsTest {

    @Test
    void takesTheFirstBareWordAsTheCommand() {
        Args args = Args.parse("daemon", "--once");

        assertEquals("daemon", args.command());
        assertTrue(args.isEnabled("once"));
    }

    @Test
    void hasNoCommandWhenTheFirstArgumentIsAnOption() {
        // `housegraph --help` has to reach the usage text rather than looking for a command
        // called "--help".
        Args args = Args.parse("--help");

        assertEquals("", args.command());
        assertTrue(args.isSet("help"));
    }

    @Test
    void hasNoCommandWhenThereAreNoArgumentsAtAll() {
        assertEquals("", Args.parse().command());
    }

    @Test
    void readsBothOptionSpellings() {
        Args args = Args.parse("sync", "--home=/tmp/one", "--branch", "main");

        assertEquals(Optional.of("/tmp/one"), args.option("home"));
        assertEquals(Optional.of("main"), args.option("branch"));
    }

    @Test
    void aBareFlagDoesNotSwallowTheOptionAfterIt() {
        // The regression this guards: consuming the next token unconditionally would make
        // --verbose's "value" be "--graph", and the graph path would vanish into a positional.
        Args args = Args.parse("run", "--verbose", "--graph", "porch.json");

        assertTrue(args.isEnabled("verbose"));
        assertEquals(Optional.of("porch.json"), args.option("graph"));
        assertEquals(List.of(), args.positionals());
    }

    @Test
    void aBareFlagAtTheEndIsStillSet() {
        Args args = Args.parse("daemon", "--once");

        assertTrue(args.isSet("once"));
        assertEquals(Optional.empty(), args.option("nothing"));
    }

    @Test
    void anExplicitlyFalseFlagIsSetButNotEnabled() {
        // So `--verbose=false` can turn off something a wrapper script switched on, rather than
        // reading as "mentioned, therefore true".
        Args args = Args.parse("run", "--verbose=false");

        assertTrue(args.isSet("verbose"));
        assertFalse(args.isEnabled("verbose"));
    }

    @Test
    void keepsPositionalsInOrder() {
        Args args = Args.parse("plugins", "update", "housegraph-camera", "housegraph-web");

        assertEquals(List.of("update", "housegraph-camera", "housegraph-web"), args.positionals());
        assertEquals(Optional.of("update"), args.positional(0));
        assertEquals(Optional.empty(), args.positional(9));
    }
}
