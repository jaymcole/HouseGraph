package io.github.jaymcole.housegraph.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrigramsTest {

    @Test
    void aWordIsPaddedWithTwoLeadingAndOneTrailingSpace() {
        assertEquals(Set.of("  c", " ca", "cat", "at "), Trigrams.of("cat"),
                "padding is what makes a word's start distinguishable from its middle");
    }

    @Test
    void eachWordIsPaddedSeparately() {
        Set<String> trigrams = Trigrams.of("to do");
        assertTrue(trigrams.contains("  d"), "the second word must get its own leading padding");
        assertTrue(trigrams.contains("  t"));
    }

    @Test
    void emptyInputYieldsNoTrigrams() {
        assertTrue(Trigrams.of("").isEmpty());
        assertTrue(Trigrams.of(null).isEmpty());
        assertTrue(Trigrams.ofAll(null).isEmpty());
    }

    @Test
    void ofAllUnionsSeveralValues() {
        assertEquals(Trigrams.of("cat dog"), Trigrams.ofAll(List.of("cat", "dog")));
    }

    @Test
    void containmentIsTheFractionOfTheQueryPresentInTheField() {
        Set<String> query = Trigrams.of("cat");
        assertEquals(1.0, Trigrams.containment(query, Trigrams.of("cat")), 1e-9);
        assertEquals(0.0, Trigrams.containment(query, Trigrams.of("zzz")), 1e-9);
    }

    @Test
    void containmentIgnoresHowLongTheFieldIs() {
        Set<String> query = Trigrams.of("add");
        double shortField = Trigrams.containment(query, Trigrams.of("add"));
        double longField = Trigrams.containment(query, Trigrams.of("add numbers together now"));
        assertEquals(shortField, longField, 1e-9,
                "a fully contained query scores the same however much else the field says — "
                        + "this is exactly what Dice would get wrong");
    }

    @Test
    void diceFallsAsTheFieldGrows() {
        Set<String> query = Trigrams.of("add");
        assertTrue(Trigrams.dice(query, Trigrams.of("add")) > Trigrams.dice(query, Trigrams.of("add numbers")),
                "Dice is the tiebreaker that prefers a tight field over a sprawling one");
    }

    @Test
    void sharesContentIgnoresPaddingOnlyOverlap() {
        assertFalse(Trigrams.sharesContent(Trigrams.of("colr"), Trigrams.of("constant")),
                "\"colr\" and \"constant\" share only \"  c\" and \" co\" — starting with the same "
                        + "letters is not a partial match");
        assertTrue(Trigrams.sharesContent(Trigrams.of("colr"), Trigrams.of("color picker")),
                "\"col\" lies inside both, so this is a real partial match");
    }
}
