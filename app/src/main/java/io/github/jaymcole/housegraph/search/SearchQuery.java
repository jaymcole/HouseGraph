package io.github.jaymcole.housegraph.search;

import io.github.jaymcole.housegraph.annotations.NodeKind;

import java.util.List;
import java.util.Set;

/**
 * A parsed query: the free text that <em>ranks</em>, and the facets that <em>filter</em>.
 *
 * <p>Those two roles are kept apart deliberately. Ranking a facet would make
 * {@code kind:control} a suggestion rather than a constraint, and a user who narrows a list
 * expects it to actually narrow. Filtering the free text would turn a typo into an empty result,
 * which is the failure the fuzzy matching exists to prevent. So facets are absolute and text is
 * advisory.
 *
 * <p>Combination follows what reads naturally: different facet keys are ANDed (a
 * {@code kind:action lib:discord} query wants both), while repeating one key ORs it (two
 * {@code kind:} terms widen rather than contradict).
 *
 * @param text                the free-text portion, normalised; empty for a facet-only query
 * @param kinds               required kinds; empty means unconstrained
 * @param excludedKinds       kinds to reject, from {@code -kind:}
 * @param libraries           required library ids or names, lower-cased; empty means unconstrained
 * @param excludedLibraries   library ids or names to reject
 * @param categories          required category-path prefixes, lower-cased
 * @param excludedCategories  category-path prefixes to reject
 * @param tags                required keywords, lower-cased
 * @param excludedTags        keywords to reject
 * @param unrecognisedFacets  keys that looked like facets but are not known, reported so a UI can
 *                            hint; their text is also folded into {@link #text}
 */
public record SearchQuery(String text,
                          Set<NodeKind> kinds,
                          Set<NodeKind> excludedKinds,
                          Set<String> libraries,
                          Set<String> excludedLibraries,
                          List<String> categories,
                          List<String> excludedCategories,
                          Set<String> tags,
                          Set<String> excludedTags,
                          List<String> unrecognisedFacets) {

    public SearchQuery {
        text = text == null ? "" : text;
        kinds = Set.copyOf(kinds);
        excludedKinds = Set.copyOf(excludedKinds);
        libraries = Set.copyOf(libraries);
        excludedLibraries = Set.copyOf(excludedLibraries);
        categories = List.copyOf(categories);
        excludedCategories = List.copyOf(excludedCategories);
        tags = Set.copyOf(tags);
        excludedTags = Set.copyOf(excludedTags);
        unrecognisedFacets = List.copyOf(unrecognisedFacets);
    }

    /** Parses a raw query string. Shorthand for {@link QueryParser#parse}. */
    public static SearchQuery of(String raw) {
        return QueryParser.parse(raw);
    }

    /** True when there is free text to rank on; false for a facet-only or empty query. */
    public boolean hasText() {
        return !text.isBlank();
    }

    /** True when at least one facet constrains the result set. */
    public boolean hasFacets() {
        return !kinds.isEmpty() || !excludedKinds.isEmpty()
                || !libraries.isEmpty() || !excludedLibraries.isEmpty()
                || !categories.isEmpty() || !excludedCategories.isEmpty()
                || !tags.isEmpty() || !excludedTags.isEmpty();
    }

    /** True when the query asks for nothing at all, and every node should be returned. */
    public boolean isEmpty() {
        return !hasText() && !hasFacets();
    }
}
