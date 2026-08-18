package io.github.jaymcole.housegraph.search;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Character-trigram sets and the two overlap measures the scorer compares them with. This is the
 * typo-tolerant half of matching — the technique PostgreSQL's {@code pg_trgm} uses.
 *
 * <h2>Why trigrams rather than edit distance or subsequence matching</h2>
 * A trigram set retains local ordering without demanding global ordering, which is exactly the
 * property that makes a half-remembered query work. {@code decompsr} shares most of its windows
 * with {@code decomposer} despite a missing letter, and {@code colr} still overlaps {@code color}.
 * Edit distance would find those too but says nothing useful about a four-character query against
 * a twenty-character field, and subsequence matching would rank {@code ObjectDecomposerNode} a
 * strong hit for {@code ode} purely because those letters appear in order.
 *
 * <h2>Padding, and why it earns prefixes for free</h2>
 * Each word is padded with two leading spaces and one trailing space before windowing, so
 * {@code cat} yields <code>{"&#160;&#160;c", "&#160;ca", "cat", "at&#160;"}</code>. The padding
 * trigrams only match at a word's start, so a query that begins a word shares more windows than
 * the same query buried mid-word. Prefix preference falls out of the representation instead of
 * needing a rule.
 *
 * @see SearchText
 */
final class Trigrams {

    private Trigrams() {
    }

    /**
     * The padded trigram set of already-{@link SearchText#normalise normalised} text, padding each
     * word separately so word starts stay distinguishable.
     *
     * @param normalised normalised text
     * @return its trigrams; empty for empty input
     */
    static Set<String> of(String normalised) {
        Set<String> trigrams = new HashSet<>();
        if (normalised == null || normalised.isEmpty()) {
            return trigrams;
        }
        for (String word : normalised.split(" ")) {
            if (!word.isEmpty()) {
                addWord(word, trigrams);
            }
        }
        return trigrams;
    }

    /** The union of {@link #of} over several already-normalised strings. */
    static Set<String> ofAll(List<String> normalised) {
        Set<String> trigrams = new HashSet<>();
        if (normalised == null) {
            return trigrams;
        }
        for (String text : normalised) {
            trigrams.addAll(of(text));
        }
        return trigrams;
    }

    /**
     * How much of the query is present in the field: {@code |Q &cap; F| / |Q|}.
     * <p>
     * This is the primary measure rather than {@link #dice}, and the reason is asymmetry. Dice
     * divides by the size of <em>both</em> sets, so a short query scores near zero against a long
     * field however completely it is contained in it — "add" against "ObjectDecomposerNode" and
     * "add" against "AddNode" would be separated mostly by length rather than by relevance.
     * Containment asks the question a user is actually asking: is what I typed in there?
     *
     * @param query the query's trigrams
     * @param field the field's trigrams
     * @return 0..1; 0 when the query is empty
     */
    static double containment(Set<String> query, Set<String> field) {
        if (query.isEmpty() || field.isEmpty()) {
            return 0;
        }
        return (double) intersectionSize(query, field) / query.size();
    }

    /**
     * Symmetric overlap: {@code 2|Q &cap; F| / (|Q| + |F|)}.
     * <p>
     * Kept as a secondary signal precisely <em>because</em> it is length-sensitive. Among fields
     * that contain the query equally well, it prefers the one with least left over — so a query of
     * "add" ranks the node actually called "Add" above one whose description merely mentions
     * adding.
     *
     * @param query the query's trigrams
     * @param field the field's trigrams
     * @return 0..1; 0 when either side is empty
     */
    static double dice(Set<String> query, Set<String> field) {
        if (query.isEmpty() || field.isEmpty()) {
            return 0;
        }
        return 2.0 * intersectionSize(query, field) / (query.size() + field.size());
    }

    /**
     * True when the two sets share a trigram that lies wholly inside a word.
     * <p>
     * Padding trigrams carry much less information than they look like they do: they say only
     * "starts with these letters", and for a short query they are a large fraction of the set. A
     * four-character query like {@code colr} shares {@code "&#160;&#160;c"} and {@code "&#160;co"}
     * with every field beginning "co", which alone reaches a containment of 0.4 — enough to clear
     * the fuzzy threshold on similarity that does not exist. Demanding one interior trigram as
     * well is what separates a real partial match from an accident of first letters.
     *
     * @param query the query's trigrams
     * @param field the field's trigrams
     * @return true when at least one space-free trigram is common to both
     */
    static boolean sharesContent(Set<String> query, Set<String> field) {
        for (String trigram : query) {
            if (trigram.indexOf(' ') < 0 && field.contains(trigram)) {
                return true;
            }
        }
        return false;
    }

    private static void addWord(String word, Set<String> out) {
        String padded = "  " + word + " ";
        for (int i = 0; i + 3 <= padded.length(); i++) {
            out.add(padded.substring(i, i + 3));
        }
    }

    private static int intersectionSize(Set<String> a, Set<String> b) {
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        int count = 0;
        for (String trigram : smaller) {
            if (larger.contains(trigram)) {
                count++;
            }
        }
        return count;
    }
}
