package io.github.jaymcole.housegraph.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandMatcherTest {

    @Test
    void matchesExactAndWithArguments() {
        assertTrue(CommandMatcher.matches("!deploy", "!deploy"));
        assertTrue(CommandMatcher.matches("!deploy prod now", "!deploy"));
    }

    @Test
    void doesNotMatchALongerWord() {
        assertFalse(CommandMatcher.matches("!deployment", "!deploy"), "trigger must be followed by whitespace or end");
    }

    @Test
    void matchingIsCaseInsensitiveAndLeadingSpaceTolerant() {
        assertTrue(CommandMatcher.matches("  !DePloY prod", "!deploy"));
    }

    @Test
    void unrelatedMessagesAndBlankTriggersDoNotMatch() {
        assertFalse(CommandMatcher.matches("hello there", "!deploy"));
        assertFalse(CommandMatcher.matches("!deploy", "  "));
        assertFalse(CommandMatcher.matches(null, "!deploy"));
    }

    @Test
    void argsAreTheTrimmedRemainderOrEmpty() {
        assertEquals("prod now", CommandMatcher.args("!deploy   prod now  ", "!deploy"));
        assertEquals("", CommandMatcher.args("!deploy", "!deploy"));
        assertEquals("", CommandMatcher.args("!deployment", "!deploy"), "no match means no args");
    }
}
