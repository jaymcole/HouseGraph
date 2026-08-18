package io.github.jaymcole.housegraph.search;

import io.github.jaymcole.housegraph.annotations.NodeKind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a raw query string into a {@link SearchQuery}.
 *
 * <p>The syntax is the one people already type into issue trackers and mail clients: bare words
 * rank, {@code key:value} filters, a leading {@code -} negates, and quotes hold a phrase together.
 *
 * <pre>
 *   kind:control repeating          control-kind nodes, ranked on "repeating"
 *   lib:discord -kind:resource      Discord's nodes except its connection nodes
 *   "image viewer"                  the phrase, not the two words separately
 * </pre>
 *
 * <h2>Nothing here is an error</h2>
 * An unrecognised key is not rejected and does not empty the result set: {@code color:red} is
 * folded into the free text and named in {@link SearchQuery#unrecognisedFacets()} so a UI can say
 * so. This follows the forgiving-parse discipline {@code PluginManifest.read} and
 * {@code AppPreferences.loadFrom} already set, and it matters more than usual here — {@code in:}
 * and {@code out:} are the facets this design deliberately does not implement yet, and a user who
 * reaches for them should get the nodes they were looking for rather than a blank list.
 * <p>
 * An unparseable <em>value</em> behaves the same way. {@code kind:sideways} names no
 * {@link NodeKind}, so rather than filtering everything out it becomes text and is reported.
 */
final class QueryParser {

    private QueryParser() {
    }

    /**
     * Parses a raw query.
     *
     * @param raw the user's input; null or blank yields an empty query matching everything
     * @return the parsed query
     */
    static SearchQuery parse(String raw) {
        List<String> textParts = new ArrayList<>();
        Set<NodeKind> kinds = new LinkedHashSet<>();
        Set<NodeKind> excludedKinds = new LinkedHashSet<>();
        Set<String> libraries = new LinkedHashSet<>();
        Set<String> excludedLibraries = new LinkedHashSet<>();
        List<String> categories = new ArrayList<>();
        List<String> excludedCategories = new ArrayList<>();
        Set<String> tags = new LinkedHashSet<>();
        Set<String> excludedTags = new LinkedHashSet<>();
        List<String> unrecognised = new ArrayList<>();

        for (String token : split(raw)) {
            boolean negated = token.startsWith("-") && token.length() > 1;
            String body = negated ? token.substring(1) : token;

            int colon = body.indexOf(':');
            if (colon <= 0 || colon == body.length() - 1) {
                textParts.add(body);
                continue;
            }

            String key = body.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = body.substring(colon + 1);
            String lowered = value.toLowerCase(Locale.ROOT);

            switch (key) {
                case "kind", "is" -> {
                    NodeKind kind = kindOf(lowered);
                    if (kind == null) {
                        unrecognised.add(body);
                        textParts.add(value);
                    } else {
                        (negated ? excludedKinds : kinds).add(kind);
                    }
                }
                case "lib", "library", "plugin" -> (negated ? excludedLibraries : libraries).add(lowered);
                case "cat", "category" -> (negated ? excludedCategories : categories).add(lowered);
                case "tag", "kw", "keyword" -> (negated ? excludedTags : tags).add(lowered);
                default -> {
                    unrecognised.add(body);
                    // Keep both halves: "in:Float" should still rank a node whose text says
                    // "float", and the key itself is occasionally the meaningful word.
                    textParts.add(key);
                    textParts.add(value);
                }
            }
        }

        return new SearchQuery(SearchText.normalise(String.join(" ", textParts)),
                kinds, excludedKinds,
                libraries, excludedLibraries,
                categories, excludedCategories,
                tags, excludedTags,
                unrecognised);
    }

    private static NodeKind kindOf(String value) {
        for (NodeKind kind : NodeKind.values()) {
            if (kind.name().toLowerCase(Locale.ROOT).equals(value)) {
                return kind;
            }
        }
        return null;
    }

    /**
     * Splits on whitespace, keeping a double-quoted run together as one token. A quote that is
     * never closed simply runs to the end of the input rather than failing — half-typed input is
     * the normal state of a search box, not a mistake to report.
     */
    private static List<String> split(String raw) {
        List<String> tokens = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
