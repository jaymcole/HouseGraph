package io.github.jaymcole.housegraph.search;

/**
 * One node that matched, with the score it earned and the field that earned it.
 *
 * <p>{@code bestField} exists for the UI that does not exist yet: a result list that can say
 * <em>why</em> a node surfaced ("matched: keywords") is the difference between a fuzzy match
 * looking clever and looking broken. Carrying it costs nothing — the scorer has already computed
 * which field won in order to combine the rest around it.
 *
 * @param node       the matching node type
 * @param score      its relevance; comparable only within one search, not across searches
 * @param bestField  the field that contributed most, or {@link SearchField#NONE} for a
 *                   facet-only query where nothing was ranked
 */
public record SearchResult(NodeDescriptor node, double score, SearchField bestField) {

    /** The searchable fields of a node, in the order the scorer weights them. */
    public enum SearchField {
        DISPLAY_NAME,
        KEYWORDS,
        SIMPLE_NAME,
        TYPE_ID,
        DESCRIPTION,
        CATEGORY_PATH,
        LIBRARY_NAME,
        PLUGIN_ID,
        /** No field was ranked, because the query had no free text. */
        NONE
    }
}
