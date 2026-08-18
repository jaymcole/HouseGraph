package io.github.jaymcole.housegraph.search;

import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.NodeMetadata;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Ranked search over every node type the {@link NodeRegistry} can offer.
 *
 * <p>The Add-Node menu answers "show me everything, arranged by folder". This answers the other
 * question — "I want to do <em>this</em>; what have I got?" — which a nested menu cannot, because
 * finding something in it requires already knowing which folder it is in. Results are therefore
 * <b>ranked, not filtered</b>: a query that matches nothing exactly still returns the closest
 * things, since a user who half-remembers a node's name is the case worth serving.
 *
 * <h2>What is matched</h2>
 * Display name, class name, save-file type id, description, keywords, category path, and the
 * owning library's id and name. Matching combines character trigrams (typo tolerance) with
 * whole-word rarity scoring — see {@link NodeScorer}.
 * <p>
 * <b>Not ports.</b> Reading a node's inputs and outputs means constructing it, which this class
 * never does. See {@code docs/engine/node-search.md}.
 *
 * <h2>Lifecycle</h2>
 * The corpus is built on first search and cached until {@link #invalidate()}, which is the
 * counterpart to {@link NodeRegistry#setRoots} — call it whenever a node library is installed,
 * updated, removed, enabled or disabled, alongside the existing {@code GraphCanvas.reloadNodeTypes()}.
 * <p>
 * The cache is a single immutable {@code Corpus} held in a {@code volatile} field and replaced
 * wholesale, exactly as {@code NodeRegistry} holds its own index and for the same reason: a reader
 * sees either the complete old corpus or the complete new one, never a half-rebuilt mixture.
 */
public final class NodeSearchIndex {

    private static final Logger log = Log.get(NodeSearchIndex.class);

    /** How many results a search returns unless the caller asks for a different number. */
    public static final int DEFAULT_LIMIT = 50;

    private final NodeRegistry registry;
    private final Function<String, String> libraryNames;

    private final Object rebuildLock = new Object();
    private volatile Corpus corpus;

    /** Everything one scan produced, replaced wholesale rather than mutated. */
    private record Corpus(List<IndexedNode> nodes, NodeScorer scorer) {
    }

    /**
     * @param registry     the source of node types
     * @param libraryNames maps a library id to its human name, for searching by library; may be
     *                     null, and may return null for an unknown id. Injected as a function
     *                     rather than as a {@code PluginCatalog} so the index stays testable
     *                     without one, following {@code AutoInstallPlan}'s precedent.
     */
    public NodeSearchIndex(NodeRegistry registry, Function<String, String> libraryNames) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.libraryNames = libraryNames == null ? id -> "" : libraryNames;
    }

    /**
     * Searches with a raw query string. See {@link QueryParser} for the syntax.
     *
     * @param query the user's input
     * @return matching nodes, best first, at most {@link #DEFAULT_LIMIT}
     */
    public List<SearchResult> search(String query) {
        return search(SearchQuery.of(query), DEFAULT_LIMIT);
    }

    /**
     * Searches with a parsed query.
     *
     * @param query the parsed query
     * @param limit the most results to return; values below 1 are treated as {@link #DEFAULT_LIMIT}
     * @return matching nodes, best first
     */
    public List<SearchResult> search(SearchQuery query, int limit) {
        Objects.requireNonNull(query, "query");
        int cap = limit < 1 ? DEFAULT_LIMIT : limit;
        Corpus current = corpus();

        // A query with facets but no text is a browse, not a search: there is nothing to rank on,
        // so every survivor is equally relevant and alphabetical order is the only honest one.
        if (!query.hasText()) {
            return current.nodes().stream()
                    .map(IndexedNode::descriptor)
                    .filter(node -> matchesFacets(query, node))
                    .sorted(Comparator.comparing(NodeDescriptor::displayName, String.CASE_INSENSITIVE_ORDER))
                    .limit(cap)
                    .map(node -> new SearchResult(node, 0, SearchResult.SearchField.NONE))
                    .toList();
        }

        NodeScorer.QueryText text = NodeScorer.QueryText.of(query.text());

        List<SearchResult> results = new ArrayList<>();
        for (IndexedNode node : current.nodes()) {
            if (!matchesFacets(query, node.descriptor())) {
                continue;
            }
            NodeScorer.Scored scored = current.scorer().score(text, node);
            if (scored.score() >= NodeScorer.MINIMUM_SCORE) {
                results.add(new SearchResult(node.descriptor(), scored.score(), scored.field()));
            }
        }

        // Descending score, then display name: ties must resolve the same way every time, or a
        // list that redraws on each keystroke reshuffles equal results under the user's cursor.
        results.sort(Comparator.comparingDouble(SearchResult::score).reversed()
                .thenComparing(result -> result.node().displayName(), String.CASE_INSENSITIVE_ORDER));

        return results.size() <= cap ? List.copyOf(results) : List.copyOf(results.subList(0, cap));
    }

    /** Every indexed node type, in registry order. */
    public List<NodeDescriptor> all() {
        return corpus().nodes().stream().map(IndexedNode::descriptor).toList();
    }

    /**
     * Discards the cached corpus so the next search re-reads the registry. Call after installing,
     * updating, removing, enabling or disabling a node library.
     */
    public void invalidate() {
        synchronized (rebuildLock) {
            corpus = null;
        }
    }

    private static boolean matchesFacets(SearchQuery query, NodeDescriptor node) {
        NodeKind kind = node.kind();
        // An untagged node has no kind, so it satisfies neither a positive nor a negative kind
        // filter by accident: it is absent from "kind:action", and it is *not* swept up by
        // "-kind:action" either, since excluding what was never claimed would be arbitrary.
        if (!query.kinds().isEmpty() && (kind == null || !query.kinds().contains(kind))) {
            return false;
        }
        if (kind != null && query.excludedKinds().contains(kind)) {
            return false;
        }

        if (!query.libraries().isEmpty() && !matchesAnyLibrary(query.libraries(), node)) {
            return false;
        }
        if (matchesAnyLibrary(query.excludedLibraries(), node)) {
            return false;
        }

        String category = node.categoryPath().toLowerCase(Locale.ROOT);
        if (!query.categories().isEmpty() && !startsWithAny(query.categories(), category)) {
            return false;
        }
        if (startsWithAny(query.excludedCategories(), category)) {
            return false;
        }

        if (!query.tags().isEmpty() && !hasAnyTag(query.tags(), node)) {
            return false;
        }
        return !hasAnyTag(query.excludedTags(), node);
    }

    /** A library facet accepts the id or the human name, since a user knows one or the other. */
    private static boolean matchesAnyLibrary(Set<String> wanted, NodeDescriptor node) {
        if (wanted.isEmpty()) {
            return false;
        }
        String id = node.pluginId().toLowerCase(Locale.ROOT);
        String name = node.libraryName().toLowerCase(Locale.ROOT);
        for (String candidate : wanted) {
            if (id.equals(candidate) || (!name.isEmpty() && name.contains(candidate))) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(List<String> prefixes, String category) {
        for (String prefix : prefixes) {
            if (category.equals(prefix) || category.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyTag(Set<String> wanted, NodeDescriptor node) {
        if (wanted.isEmpty()) {
            return false;
        }
        for (String keyword : node.keywords()) {
            if (wanted.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private Corpus corpus() {
        Corpus current = corpus;
        if (current != null) {
            return current;
        }
        synchronized (rebuildLock) {
            if (corpus == null) {
                corpus = build();
            }
            return corpus;
        }
    }

    private Corpus build() {
        List<NodeRegistry.Entry> entries = registry.discover();
        List<IndexedNode> nodes = new ArrayList<>(entries.size());
        for (NodeRegistry.Entry entry : entries) {
            nodes.add(IndexedNode.of(describe(entry)));
        }
        log.debug("Indexed {} node types for search", nodes.size());
        return new Corpus(List.copyOf(nodes), new NodeScorer(nodes));
    }

    private NodeDescriptor describe(NodeRegistry.Entry entry) {
        NodeMetadata metadata = NodeMetadata.of(entry.nodeClass());
        String libraryName = libraryNames.apply(entry.pluginId());
        return new NodeDescriptor(entry.nodeClass(),
                NodeRegistry.persistentTypeId(entry.nodeClass()),
                entry.displayName(),
                entry.nodeClass().getSimpleName(),
                entry.categoryPath(),
                entry.pluginId(),
                libraryName == null ? "" : libraryName,
                metadata.description(),
                metadata.keywords(),
                metadata.kind());
    }
}
