package io.github.jaymcole.housegraph.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchTextTest {

    @Test
    void normaliseLowercasesAndCollapsesSeparators() {
        assertEquals("image viewer", SearchText.normalise("Image  Viewer"));
        assertEquals("mylib discord", SearchText.normalise("mylib.discord"),
                "a dotted category path should read as two words, not one");
        assertEquals("if boolean", SearchText.normalise("If (Boolean)"));
    }

    @Test
    void normaliseTrimsLeadingAndTrailingSeparators() {
        assertEquals("add", SearchText.normalise("  ...Add!  "),
                "padding punctuation must not leave a leading or trailing space, which would "
                        + "change the word's trigrams");
    }

    @Test
    void normaliseHandlesNullAndEmpty() {
        assertEquals("", SearchText.normalise(null));
        assertEquals("", SearchText.normalise(""));
        assertEquals("", SearchText.normalise("---"));
    }

    @Test
    void tokeniseSplitsCamelCase() {
        assertEquals(List.of("trigger", "repeating", "node"), SearchText.tokenise("TriggerRepeatingNode"));
        assertEquals(List.of("add", "node"), SearchText.tokenise("addNode"));
    }

    @Test
    void tokeniseSplitsAnAcronymBeforeItsLastCapital() {
        assertEquals(List.of("http", "server"), SearchText.tokenise("HTTPServer"),
                "the S starts \"server\"; splitting after it would yield \"https\" and \"erver\"");
        assertEquals(List.of("url"), SearchText.tokenise("URL"),
                "a trailing acronym has no following word to break off");
    }

    @Test
    void tokeniseSplitsLetterDigitBoundaries() {
        assertEquals(List.of("base", "64", "encode"), SearchText.tokenise("base64Encode"));
    }

    @Test
    void tokeniseSplitsOnSeparators() {
        assertEquals(List.of("list", "to", "string"), SearchText.tokenise("List to String"));
        assertEquals(List.of("mylib", "discord"), SearchText.tokenise("mylib.discord"));
    }

    @Test
    void tokeniseHandlesNullAndEmpty() {
        assertTrue(SearchText.tokenise(null).isEmpty());
        assertTrue(SearchText.tokenise("").isEmpty());
        assertTrue(SearchText.tokenise("   ").isEmpty());
    }

    @Test
    void tokeniseAllFlattensAListOfValues() {
        assertEquals(List.of("plus", "sum", "to", "string"),
                SearchText.tokeniseAll(List.of("plus", "sum", "toString")));
        assertTrue(SearchText.tokeniseAll(null).isEmpty());
    }
}
