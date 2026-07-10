package io.github.jaymcole.housegraph.ml;

import ai.djl.modality.Classifications;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Headless tests for the squirrel/bird/other/none decision policy. A
 * {@link Classifications} is built directly from labels + probabilities, so nothing here
 * loads a model or the native runtime.
 */
class AnimalVerdictTest {

    private static Classifications of(String[] labels, double[] probs) {
        return new Classifications(List.of(labels), java.util.Arrays.stream(probs).boxed().toList());
    }

    @Test
    void topSquirrelIsClassifiedAsSquirrel() {
        Classifications c = of(new String[]{"fox squirrel", "marmot"}, new double[]{0.9, 0.1});
        AnimalVerdict.Result r = AnimalVerdict.decide(c, 0.1f, 5);
        assertEquals(AnimalVerdict.SQUIRREL, r.category());
        assertEquals("fox squirrel", r.label());
        assertEquals(0.9, r.confidence(), 1e-9);
    }

    @Test
    void birdSpeciesWithoutTheWordBirdStillCountsAsBird() {
        Classifications c = of(new String[]{"goldfinch", "junco"}, new double[]{0.7, 0.3});
        assertEquals(AnimalVerdict.BIRD, AnimalVerdict.decide(c, 0.1f, 5).category());
    }

    @Test
    void animalBelowTopButAboveThresholdIsStillFound() {
        // Model's #1 guess is background; the squirrel is #2 but still clears the threshold.
        Classifications c = of(new String[]{"chainlink fence", "fox squirrel"}, new double[]{0.6, 0.35});
        AnimalVerdict.Result r = AnimalVerdict.decide(c, 0.1f, 5);
        assertEquals(AnimalVerdict.SQUIRREL, r.category());
        assertEquals(0.35, r.confidence(), 1e-9);
    }

    @Test
    void animalBelowThresholdIsNotAccepted() {
        // The squirrel prediction exists but is too weak; top guess is a confident non-animal.
        Classifications c = of(new String[]{"chainlink fence", "fox squirrel"}, new double[]{0.8, 0.05});
        AnimalVerdict.Result r = AnimalVerdict.decide(c, 0.1f, 5);
        assertEquals(AnimalVerdict.OTHER, r.category());
        assertEquals("chainlink fence", r.label());
    }

    @Test
    void confidentNonAnimalIsOther() {
        Classifications c = of(new String[]{"mailbox", "picket fence"}, new double[]{0.75, 0.2});
        assertEquals(AnimalVerdict.OTHER, AnimalVerdict.decide(c, 0.1f, 5).category());
    }

    @Test
    void nothingConfidentIsNone() {
        Classifications c = of(new String[]{"mailbox", "picket fence"}, new double[]{0.05, 0.04});
        assertEquals(AnimalVerdict.NONE, AnimalVerdict.decide(c, 0.1f, 5).category());
    }

    @Test
    void categorizeIsCaseInsensitiveAndNullForNonAnimals() {
        assertEquals(AnimalVerdict.SQUIRREL, AnimalVerdict.categorize("Fox Squirrel"));
        assertEquals(AnimalVerdict.BIRD, AnimalVerdict.categorize("MAGPIE"));
        assertNull(AnimalVerdict.categorize("coffee mug"));
    }
}
