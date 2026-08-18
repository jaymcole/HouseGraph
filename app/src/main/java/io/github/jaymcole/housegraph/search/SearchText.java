package io.github.jaymcole.housegraph.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the text a node carries into the form the matchers compare: a normalised string, and a
 * list of word tokens.
 *
 * <h2>Why this is its own class</h2>
 * Both matchers depend on agreeing about what a "word" is, and they arrive at it from opposite
 * directions — {@link Trigrams} slides a window over normalised characters, while the rarity
 * scorer counts whole tokens. If they normalised separately they would drift, and the symptom
 * would be a query that scores well on one signal and zero on the other for no reason a user
 * could see. One place, used by both.
 *
 * <h2>camelCase is the whole point</h2>
 * Java class names are this codebase's richest source of search terms and they arrive
 * unsegmented: {@code TriggerRepeatingNode} contains "trigger" and "repeating" but a naive
 * lowercase leaves one 21-character word that matches neither. Splitting it is what lets a user
 * type "repeating" and find it.
 * <p>
 * The universal {@code Node} suffix is deliberately <em>not</em> stripped here. It does not need
 * to be: it appears in essentially every node's tokens, so its inverse document frequency in
 * {@code NodeScorer} is near zero and it contributes almost nothing to a score on its own. A
 * hand-maintained stopword list would be one more thing to keep in sync with the corpus for no
 * gain.
 *
 * @see Trigrams
 */
final class SearchText {

    private SearchText() {
    }

    /**
     * Lower-cases and flattens everything that is not a letter or digit to a single space.
     * <p>
     * {@code Locale.ROOT} rather than the default locale, matching {@code RepositoryUrls} and
     * {@code GitHubReleases}: a Turkish default locale lower-cases {@code I} to a dotless
     * {@code ı}, which would quietly stop {@code If} matching {@code if} for those users only.
     *
     * @param text the raw text; null is treated as empty
     * @return the normalised form, trimmed, with runs of separators collapsed to one space
     */
    static String normalise(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (pendingSpace && out.length() > 0) {
                    out.append(' ');
                }
                pendingSpace = false;
                out.append(Character.toLowerCase(c));
            } else {
                pendingSpace = true;
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Splits text into lower-cased word tokens, breaking on separators and on the three case
     * boundaries that carry meaning in identifiers:
     * <ul>
     *   <li>lower to upper — {@code addNode} to {@code [add, node]};</li>
     *   <li>the tail of an acronym run — {@code HTTPServer} to {@code [http, server]}, splitting
     *       before the last capital rather than after it, because that capital starts the next
     *       word;</li>
     *   <li>letter to digit and back — {@code base64Encode} to {@code [base, 64, encode]}.</li>
     * </ul>
     *
     * @param text the raw text; null is treated as empty
     * @return the tokens in order, possibly empty, never null
     */
    static List<String> tokenise(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                flush(current, tokens);
                continue;
            }
            if (current.length() > 0 && startsNewToken(text, i)) {
                flush(current, tokens);
            }
            current.append(Character.toLowerCase(c));
        }
        flush(current, tokens);
        return tokens;
    }

    /** Tokenises several strings into one list, for a field that is itself a list (keywords). */
    static List<String> tokeniseAll(List<String> texts) {
        List<String> tokens = new ArrayList<>();
        if (texts == null) {
            return tokens;
        }
        for (String text : texts) {
            tokens.addAll(tokenise(text));
        }
        return tokens;
    }

    private static boolean startsNewToken(String text, int i) {
        char previous = text.charAt(i - 1);
        char c = text.charAt(i);

        if (Character.isDigit(c) != Character.isDigit(previous)) {
            return true;
        }
        if (!Character.isUpperCase(c)) {
            return false;
        }
        if (Character.isLowerCase(previous)) {
            return true;
        }
        // Inside a run of capitals, only the last one starts a word, and only when a lowercase
        // letter follows it: the S in "HTTPServer" begins "server", but the T in "HTTP" does not.
        return Character.isUpperCase(previous)
                && i + 1 < text.length()
                && Character.isLowerCase(text.charAt(i + 1));
    }

    private static void flush(StringBuilder current, List<String> tokens) {
        if (current.length() > 0) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }
}
