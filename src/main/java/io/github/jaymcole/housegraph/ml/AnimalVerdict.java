package io.github.jaymcole.housegraph.ml;

import ai.djl.modality.Classifications;

import java.util.List;
import java.util.Set;

/**
 * The pure decision policy behind {@code AnimalClassifierNode}: given an
 * {@link ImageNetClassifier} result, decide whether the frame shows a squirrel, a bird,
 * some other confidently-seen thing, or nothing of note. Kept separate from the node —
 * free of JavaFX and of any model loading — so it stays headless-testable (a
 * {@link Classifications} can be built by hand from labels + probabilities, no native
 * runtime needed) and reusable if other nodes ask the same question.
 * <p>
 * ImageNet-1k has a single squirrel class ("fox squirrel") but scatters birds across
 * ~60 species whose names rarely contain the word "bird" (goldfinch, junco, magpie…),
 * so birds are recognised by a curated set of label tokens rather than that word alone.
 */
public final class AnimalVerdict {

    public static final String SQUIRREL = "squirrel";
    public static final String BIRD = "bird";
    public static final String OTHER = "other";
    public static final String NONE = "none";

    private static final Set<String> SQUIRREL_TOKENS = Set.of("squirrel");
    private static final Set<String> BIRD_TOKENS = Set.of(
            "bird", "finch", "goldfinch", "junco", "brambling", "bunting", "robin", "jay",
            "magpie", "chickadee", "bulbul", "ouzel", "cock", "hen", "ostrich", "kite",
            "eagle", "vulture", "owl", "grouse", "ptarmigan", "prairie chicken", "peacock",
            "quail", "partridge", "parrot", "african grey", "macaw", "cockatoo", "lorikeet",
            "coucal", "bee eater", "hornbill", "hummingbird", "jacamar", "toucan", "drake",
            "merganser", "goose", "swan", "stork", "spoonbill", "flamingo", "heron", "egret",
            "bittern", "crane", "limpkin", "gallinule", "coot", "bustard", "turnstone",
            "sandpiper", "redshank", "dowitcher", "oystercatcher", "pelican", "penguin",
            "albatross");

    private AnimalVerdict() {
    }

    /**
     * The outcome of classifying one frame.
     *
     * @param category   one of {@link #SQUIRREL}, {@link #BIRD}, {@link #OTHER}, {@link #NONE}
     * @param confidence probability of the label that decided the category, in [0, 1]
     * @param label      the raw ImageNet label behind the decision (null only when there
     *                   were no predictions at all)
     */
    public record Result(String category, double confidence, String label) {
    }

    /**
     * Decide the coarse animal category for a classifier result.
     *
     * <p>The predictions are scanned in descending probability; the first squirrel- or
     * bird-type label that clears {@code minConfidence} wins, so the animal still counts
     * when the model's #1 guess is some background object. If none is found, the result is
     * {@link #OTHER} when the top prediction itself cleared the threshold (the model was
     * confident about <em>something</em>) and {@link #NONE} otherwise.
     *
     * @param result        the classifier output (predictions in any order; ranked here)
     * @param minConfidence  minimum probability to accept, in [0, 1]
     * @param topK          how many of the ranked predictions to consider for an animal
     */
    public static Result decide(Classifications result, float minConfidence, int topK) {
        List<Classifications.Classification> ranked = result.topK(topK);
        if (ranked.isEmpty()) {
            return new Result(NONE, 0.0, null);
        }
        for (Classifications.Classification c : ranked) {
            String bucket = categorize(c.getClassName());
            if (bucket != null && c.getProbability() >= minConfidence) {
                return new Result(bucket, c.getProbability(), c.getClassName());
            }
        }
        Classifications.Classification best = ranked.get(0);
        String category = best.getProbability() >= minConfidence ? OTHER : NONE;
        return new Result(category, best.getProbability(), best.getClassName());
    }

    /** Maps a raw ImageNet label to {@link #SQUIRREL}/{@link #BIRD}, or null if neither. */
    public static String categorize(String label) {
        String lower = label.toLowerCase();
        if (matches(lower, SQUIRREL_TOKENS)) {
            return SQUIRREL;
        }
        if (matches(lower, BIRD_TOKENS)) {
            return BIRD;
        }
        return null;
    }

    private static boolean matches(String label, Set<String> tokens) {
        for (String token : tokens) {
            if (label.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
