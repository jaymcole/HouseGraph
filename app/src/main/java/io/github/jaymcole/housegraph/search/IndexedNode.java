package io.github.jaymcole.housegraph.search;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.jaymcole.housegraph.search.SearchResult.SearchField;

/**
 * A {@link NodeDescriptor} with everything the scorer needs precomputed: each field's normalised
 * text, each field's trigram set, and the node's full token list.
 *
 * <p>Precomputing is what keeps searching cheap enough to run on every keystroke. Normalising and
 * windowing a node's text costs the same whether it happens once at index build or once per
 * query, and a search box does the latter tens of times per second across the whole corpus.
 *
 * <p>{@code KEYWORDS} is the one multi-valued field, so it keeps its values separately as well as
 * a unioned trigram set: exact and prefix tests need to look at one keyword at a time (a query of
 * "sum" exactly matches the keyword {@code sum}, not the concatenation of every keyword), while
 * the fuzzy test wants them pooled.
 */
record IndexedNode(NodeDescriptor descriptor,
                   Map<SearchField, String> normalised,
                   Map<SearchField, Set<String>> trigrams,
                   List<String> keywordValues,
                   List<String> tokens) {

    /** Builds the indexed form of a descriptor. */
    static IndexedNode of(NodeDescriptor descriptor) {
        Map<SearchField, String> normalised = new EnumMap<>(SearchField.class);
        normalised.put(SearchField.DISPLAY_NAME, SearchText.normalise(descriptor.displayName()));
        normalised.put(SearchField.SIMPLE_NAME, SearchText.normalise(descriptor.simpleName()));
        normalised.put(SearchField.TYPE_ID, SearchText.normalise(descriptor.typeId()));
        normalised.put(SearchField.DESCRIPTION, SearchText.normalise(descriptor.description()));
        normalised.put(SearchField.CATEGORY_PATH, SearchText.normalise(descriptor.categoryPath()));
        normalised.put(SearchField.LIBRARY_NAME, SearchText.normalise(descriptor.libraryName()));
        normalised.put(SearchField.PLUGIN_ID, SearchText.normalise(descriptor.pluginId()));

        List<String> keywordValues = new ArrayList<>();
        for (String keyword : descriptor.keywords()) {
            String value = SearchText.normalise(keyword);
            if (!value.isEmpty()) {
                keywordValues.add(value);
            }
        }
        normalised.put(SearchField.KEYWORDS, String.join(" ", keywordValues));

        Map<SearchField, Set<String>> trigrams = new EnumMap<>(SearchField.class);
        for (Map.Entry<SearchField, String> entry : normalised.entrySet()) {
            trigrams.put(entry.getKey(), Trigrams.of(entry.getValue()));
        }
        trigrams.put(SearchField.KEYWORDS, Trigrams.ofAll(keywordValues));

        return new IndexedNode(descriptor, normalised, trigrams, keywordValues, tokensOf(descriptor));
    }

    /**
     * Every token this node contributes to the corpus, as one bag.
     * <p>
     * The class's simple name is tokenised rather than the display name alone, because the two
     * carry different terms — {@code ObjectDecomposerNode} yields "object", "decomposer" and
     * "node" while its display name may only say "Decompose". Both are wanted.
     */
    private static List<String> tokensOf(NodeDescriptor descriptor) {
        List<String> tokens = new ArrayList<>();
        tokens.addAll(SearchText.tokenise(descriptor.displayName()));
        tokens.addAll(SearchText.tokenise(descriptor.simpleName()));
        tokens.addAll(SearchText.tokenise(descriptor.typeId()));
        tokens.addAll(SearchText.tokenise(descriptor.description()));
        tokens.addAll(SearchText.tokenise(descriptor.categoryPath()));
        tokens.addAll(SearchText.tokenise(descriptor.libraryName()));
        tokens.addAll(SearchText.tokenise(descriptor.pluginId()));
        tokens.addAll(SearchText.tokeniseAll(descriptor.keywords()));
        return tokens;
    }
}
