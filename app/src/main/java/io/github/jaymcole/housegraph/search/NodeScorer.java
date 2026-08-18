package io.github.jaymcole.housegraph.search;

import io.github.jaymcole.housegraph.search.SearchResult.SearchField;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scores one node against one query. Two signals, combined.
 *
 * <h2>1. Field matching, tiered</h2>
 * Each field is compared with the query and lands in a tier: exact, prefix, substring, or fuzzy.
 * The tiers are separated widely enough that no amount of fuzzy similarity can outrank a literal
 * prefix hit. That ordering is not a tuning preference — a user who types the first four letters
 * of a node's name and watches something else jump to the top has been told the search is broken,
 * and will not trust it again.
 * <p>
 * Fields are then combined so the <em>best</em> one dominates and the others only break ties:
 * <pre>
 *   textScore = max(w · fieldScore) + 0.25 · Σ remaining (w · fieldScore)
 * </pre>
 * Summing them all equally instead would let a node that matches four fields weakly beat one that
 * matches its own name exactly, which is backwards — relevance comes from the strength of the
 * best evidence, not the quantity of weak evidence.
 *
 * <h2>2. Token rarity (BM25)</h2>
 * Whole-word matching weighted by how rare the word is across the corpus. This is what makes
 * multi-word queries behave and, more importantly, what defuses the {@code Node} suffix every
 * class here carries: a term appearing in nearly every document gets an inverse document
 * frequency near zero and stops influencing the ranking on its own. No stopword list to maintain.
 *
 * <h2>Tuning</h2>
 * Every constant below is a starting point checked against the real built-in library, not a
 * derived truth. They live here, in {@code app}, precisely so that changing them never touches
 * the published API or forces an out-of-tree library to be rebuilt.
 */
final class NodeScorer {

    /**
     * Below this, a match is noise rather than a weak hit. Calibrated against the built-in
     * library: genuine misspellings of a real node score 0.33 and up, while the best coincidental
     * matches for a query naming nothing sit below 0.26.
     */
    static final double MINIMUM_SCORE = 0.25;

    /** Trigram containment below this is coincidence — short queries overlap almost everything. */
    private static final double FUZZY_FLOOR = 0.40;

    private static final double TIER_EXACT = 1.00;
    private static final double TIER_PREFIX = 0.80;
    private static final double TIER_CONTAINS = 0.60;

    /** Caps a fuzzy hit below the substring tier, so fuzz can never outrank a literal match. */
    private static final double FUZZY_CEILING = 0.45;

    /** How much the non-best fields contribute. Lucene's dis_max tie-breaker, same purpose. */
    private static final double TIE_BREAKER = 0.25;

    /** How much whole-word rarity can add on top of field matching. */
    private static final double RARITY_WEIGHT = 0.35;

    private static final double K1 = 1.2;

    /**
     * BM25's length normalisation, at two thirds of the usual 0.75. These documents are short and
     * their length differences are mostly incidental — a node with a fuller description is not
     * less relevant than a terse one, and penalising it as heavily as BM25 penalises a long
     * article would punish exactly the authors who documented their nodes best.
     */
    private static final double B = 0.5;

    private static final Map<SearchField, Double> WEIGHTS = weights();

    private final Map<String, Integer> documentFrequency;
    private final int documentCount;
    private final double averageLength;

    /**
     * @param nodes the whole corpus, from which term statistics are derived
     */
    NodeScorer(List<IndexedNode> nodes) {
        Map<String, Integer> frequency = new HashMap<>();
        long totalLength = 0;
        for (IndexedNode node : nodes) {
            totalLength += node.tokens().size();
            for (String term : Set.copyOf(node.tokens())) {
                frequency.merge(term, 1, Integer::sum);
            }
        }
        this.documentFrequency = Map.copyOf(frequency);
        this.documentCount = nodes.size();
        this.averageLength = nodes.isEmpty() ? 1 : (double) totalLength / nodes.size();
    }

    /**
     * Scores a node against a query's free text.
     *
     * @param query the query, with its phrase and per-term forms precomputed once per search
     * @param node  the node to score
     * @return the score and the field that earned it
     */
    Scored score(QueryText query, IndexedNode node) {
        double best = 0;
        double rest = 0;
        SearchField bestField = SearchField.NONE;

        for (Map.Entry<SearchField, Double> entry : WEIGHTS.entrySet()) {
            SearchField field = entry.getKey();
            double weighted = entry.getValue() * fieldScore(query, field, node);
            if (weighted > best) {
                rest += best;
                best = weighted;
                bestField = field;
            } else {
                rest += weighted;
            }
        }

        double score = best + TIE_BREAKER * rest + RARITY_WEIGHT * rarity(query.tokens(), node);
        return new Scored(score, bestField);
    }

    /**
     * A query in both the forms the scorer needs, computed once per search rather than once per
     * node: the phrase as typed, and its individual terms.
     *
     * @param phrase          the normalised free text
     * @param phraseTrigrams  its trigrams as one string
     * @param tokens          its word tokens
     * @param tokenTrigrams   each token's trigrams, positionally aligned with {@code tokens}
     */
    record QueryText(String phrase, Set<String> phraseTrigrams, List<String> tokens, List<Set<String>> tokenTrigrams) {

        /** Precomputes both forms of an already-normalised query. */
        static QueryText of(String normalised) {
            List<String> tokens = SearchText.tokenise(normalised);
            List<Set<String>> tokenTrigrams = new ArrayList<>(tokens.size());
            for (String token : tokens) {
                tokenTrigrams.add(Trigrams.of(token));
            }
            return new QueryText(normalised, Trigrams.of(normalised), List.copyOf(tokens), List.copyOf(tokenTrigrams));
        }
    }

    /** A node's score and the field that contributed most of it. */
    record Scored(double score, SearchField field) {
    }

    /**
     * A field's score is the better of two readings of the query: as one phrase, and as a set of
     * independent terms.
     * <p>
     * The phrase reading is what makes {@code "list to string"} match the node of that name
     * outright. On its own, though, it dilutes: a query where only one word is relevant —
     * {@code "image node"}, or the free text left over after an unrecognised facet — spreads its
     * trigrams across words the field does not contain, and the overlap falls under the fuzzy
     * threshold even though the one word that mattered matched perfectly. Averaging the per-term
     * scores recovers that case, and taking the better of the two means neither reading can cost
     * a node a match the other would have found.
     */
    private double fieldScore(QueryText query, SearchField field, IndexedNode node) {
        double phrase = fieldScore(query.phrase(), query.phraseTrigrams(), field, node);
        if (query.tokens().size() < 2) {
            return phrase;
        }
        double total = 0;
        for (int i = 0; i < query.tokens().size(); i++) {
            total += fieldScore(query.tokens().get(i), query.tokenTrigrams().get(i), field, node);
        }
        return Math.max(phrase, total / query.tokens().size());
    }

    private double fieldScore(String query, Set<String> queryTrigrams, SearchField field, IndexedNode node) {
        if (field == SearchField.KEYWORDS) {
            return keywordScore(query, queryTrigrams, node);
        }
        String text = node.normalised().get(field);
        double literal = literalScore(query, text);
        if (literal > 0) {
            return literal;
        }
        return fuzzyScore(queryTrigrams, node.trigrams().get(field));
    }

    /**
     * Keywords are tested one at a time for the literal tiers, because a keyword list is a set of
     * alternatives rather than a sentence: a query of "sum" exactly matches the keyword
     * {@code sum}, and would only ever be a substring of the joined {@code "plus sum arithmetic"}.
     * The fuzzy test uses the pooled trigrams, where that distinction does not apply.
     */
    private double keywordScore(String query, Set<String> queryTrigrams, IndexedNode node) {
        double best = 0;
        for (String keyword : node.keywordValues()) {
            best = Math.max(best, literalScore(query, keyword));
            if (best >= TIER_EXACT) {
                return best;
            }
        }
        if (best > 0) {
            return best;
        }
        return fuzzyScore(queryTrigrams, node.trigrams().get(SearchField.KEYWORDS));
    }

    private static double literalScore(String query, String text) {
        if (text == null || text.isEmpty() || query.isEmpty()) {
            return 0;
        }
        if (text.equals(query)) {
            return TIER_EXACT;
        }
        if (text.startsWith(query)) {
            return TIER_PREFIX;
        }
        if (text.contains(query)) {
            return TIER_CONTAINS;
        }
        return 0;
    }

    /**
     * Containment carries the fuzzy signal, with Dice folded in at a quarter weight to prefer a
     * tight field over a sprawling one that happens to contain the same windows.
     */
    private static double fuzzyScore(Set<String> queryTrigrams, Set<String> fieldTrigrams) {
        if (fieldTrigrams == null || fieldTrigrams.isEmpty()) {
            return 0;
        }
        if (!Trigrams.sharesContent(queryTrigrams, fieldTrigrams)) {
            return 0;
        }
        double containment = Trigrams.containment(queryTrigrams, fieldTrigrams);
        if (containment < FUZZY_FLOOR) {
            return 0;
        }
        double dice = Trigrams.dice(queryTrigrams, fieldTrigrams);
        return FUZZY_CEILING * (0.75 * containment + 0.25 * dice);
    }

    /**
     * BM25 over the node's token bag, normalised by the best score the query could possibly
     * achieve so the result lands in roughly 0..1 and stays comparable with the field scores it
     * is added to.
     * <p>
     * A query term absent from the whole corpus still contributes to the denominator, so a query
     * whose words nobody uses scores low rather than being quietly rescaled upward. A wholly
     * misspelled query therefore scores zero here and rests entirely on trigrams, which is the
     * intended division of labour.
     */
    private double rarity(List<String> queryTokens, IndexedNode node) {
        if (queryTokens.isEmpty() || documentCount == 0) {
            return 0;
        }
        Map<String, Integer> frequencies = termFrequencies(node.tokens());
        double length = node.tokens().size();
        double score = 0;
        double ceiling = 0;

        for (String term : queryTokens) {
            double idf = idf(term);
            ceiling += idf;
            int frequency = frequencies.getOrDefault(term, 0);
            if (frequency == 0) {
                continue;
            }
            double denominator = frequency + K1 * (1 - B + B * length / averageLength);
            score += idf * frequency * (K1 + 1) / denominator;
        }

        if (ceiling <= 0) {
            return 0;
        }
        return Math.min(1, score / (ceiling * (K1 + 1)));
    }

    private double idf(String term) {
        int frequency = documentFrequency.getOrDefault(term, 0);
        return Math.log(1 + (documentCount - frequency + 0.5) / (frequency + 0.5));
    }

    private static Map<String, Integer> termFrequencies(List<String> tokens) {
        Map<String, Integer> frequencies = new HashMap<>();
        for (String token : tokens) {
            frequencies.merge(token, 1, Integer::sum);
        }
        return frequencies;
    }

    private static Map<SearchField, Double> weights() {
        Map<SearchField, Double> weights = new EnumMap<>(SearchField.class);
        weights.put(SearchField.DISPLAY_NAME, 1.00);
        weights.put(SearchField.KEYWORDS, 0.90);
        weights.put(SearchField.SIMPLE_NAME, 0.80);
        weights.put(SearchField.TYPE_ID, 0.80);
        weights.put(SearchField.DESCRIPTION, 0.60);
        weights.put(SearchField.CATEGORY_PATH, 0.50);
        weights.put(SearchField.LIBRARY_NAME, 0.45);
        weights.put(SearchField.PLUGIN_ID, 0.40);
        return weights;
    }
}
