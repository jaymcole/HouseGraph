package io.github.jaymcole.housegraph.search;

import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end search over the fixture library in {@code search.fixture.nodes}, not over the app's
 * real built-ins — the same discipline {@code NodeRegistryTest} follows, so adding a node to the
 * app never breaks a ranking assertion here.
 */
class NodeSearchIndexTest {

    private static final ClassLoader LOADER = NodeSearchIndexTest.class.getClassLoader();
    private static final String FIXTURE_PACKAGE = "io.github.jaymcole.housegraph.search.fixture.nodes";
    private static final String LIBRARY_ID = "fixturelib";

    @Test
    void everyFixtureNodeIsIndexed() {
        assertEquals(6, index().all().size(),
                "all six fixture node types should be discovered and described");
    }

    @Test
    void aNodeIsFoundByItsDisplayName() {
        assertEquals("Color Picker", top("color picker"));
    }

    @Test
    void aNodeIsFoundByAKeywordItsNameDoesNotContain() {
        assertEquals("Add Numbers", top("plus"),
                "the whole point of keywords is finding a node you cannot name");
        assertEquals("Add Numbers", top("sum"));
    }

    @Test
    void aNodeIsFoundByItsDescription() {
        assertEquals("Repeating Timer", top("interval"));
    }

    @Test
    void aMisspelledQueryStillFindsTheNode() {
        assertEquals("Color Picker", top("colr"));
        assertEquals("Repeating Timer", top("repeatng"));
    }

    @Test
    void aQueryMatchingNothingReturnsNothingRatherThanTheClosestJunk() {
        assertTrue(index().search("xyzzy").isEmpty(),
                "a search box that always shows something teaches users to ignore it");
    }

    @Test
    void anAcronymInAClassNameIsSearchable() {
        assertEquals("HTTP Server", top("server"),
                "HTTPServerFixtureNode only yields \"server\" if the tokeniser splits the acronym");
    }

    @Test
    void theKindFacetFilters() {
        List<SearchResult> results = index().search("kind:control");
        assertEquals(List.of("Repeating Timer"), displayNames(results));
    }

    @Test
    void aNegatedKindFacetExcludes() {
        assertFalse(displayNames(index().search("-kind:control")).contains("Repeating Timer"));
    }

    @Test
    void theCategoryFacetFilters() {
        assertEquals(List.of("Add Numbers", "Color Picker", "UntaggedFixtureNode"),
                displayNames(index().search("cat:alpha")));
    }

    @Test
    void theTagFacetFilters() {
        assertEquals(List.of("Add Numbers"), displayNames(index().search("tag:plus")));
    }

    @Test
    void theLibraryFacetMatchesTheIdOrTheHumanName() {
        assertEquals(6, index().search("lib:fixturelib").size());
        assertEquals(6, index().search("lib:fixture").size(),
                "\"Fixture Library\" is the catalog name, and a user knows one or the other");
        assertTrue(index().search("lib:nosuchlibrary").isEmpty());
    }

    @Test
    void facetsCombineWithFreeText() {
        assertEquals(List.of("Add Numbers"), displayNames(index().search("cat:alpha plus")));
        assertTrue(index().search("cat:beta plus").isEmpty(),
                "the facet must actually constrain, not merely nudge the ranking");
    }

    @Test
    void aFacetOnlyQueryBrowsesInAlphabeticalOrder() {
        List<SearchResult> results = index().search("kind:action");
        assertEquals(List.of("Color Picker"), displayNames(results));
        assertEquals(SearchResult.SearchField.NONE, results.get(0).bestField(),
                "nothing was ranked, so no field earned the result");
    }

    @Test
    void anUntaggedNodeMatchesNoKindFacetButIsStillSearchableByText() {
        assertFalse(displayNames(index().search("kind:data")).contains("UntaggedFixtureNode"));
        assertFalse(displayNames(index().search("kind:action")).contains("UntaggedFixtureNode"));
        assertEquals("UntaggedFixtureNode", top("untagged"),
                "declaring no kind costs a node its facet, not its findability");
    }

    @Test
    void anUntaggedNodeIsNotSweptUpByANegatedKindEither() {
        assertTrue(displayNames(index().search("-kind:data")).contains("UntaggedFixtureNode"),
                "excluding a kind the node never claimed would be arbitrary");
    }

    @Test
    void implementingAutoStartableInfersResource() {
        assertEquals(NodeKind.RESOURCE, kindOf("Auto Start Thing"),
                "a running/stopped lifecycle is a resource even when the author said nothing");
    }

    @Test
    void aDeclaredKindWinsOverTheAutoStartableFallback() {
        assertEquals(NodeKind.CONTROL, kindOf("Repeating Timer"),
                "a repeating trigger implements AutoStartable too; letting the interface win "
                        + "would misfile every one of them as a resource");
    }

    @Test
    void anUnrecognisedFacetStillReturnsResults() {
        assertFalse(index().search("in:Float color").isEmpty(),
                "a facet we do not implement must degrade to free text, not to a blank list");
    }

    @Test
    void resultsAreOrderedBestFirst() {
        List<SearchResult> results = index().search("color");
        assertEquals("Color Picker", results.get(0).node().displayName());
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i - 1).score() >= results.get(i).score(),
                    "scores must descend, or the list is not a ranking");
        }
    }

    @Test
    void theLimitCapsTheResultCount() {
        assertEquals(1, index().search(SearchQuery.of("kind:control kind:action kind:data"), 1).size());
    }

    @Test
    void invalidateRebuildsAfterTheRegistrysRootsChange() {
        NodeRegistry registry = new NodeRegistry(List.of(scanRoot(FIXTURE_PACKAGE + ".alpha")));
        NodeSearchIndex index = new NodeSearchIndex(registry, id -> "Fixture Library");
        assertEquals(3, index.all().size());

        registry.setRoots(List.of(scanRoot(FIXTURE_PACKAGE)));
        assertEquals(3, index.all().size(),
                "until it is told, the index keeps serving the corpus it built");

        index.invalidate();
        assertEquals(6, index.all().size(), "invalidate() is what picks up an installed library");
    }

    @Test
    void aNullLibraryNameLookupIsTolerated() {
        NodeSearchIndex index = new NodeSearchIndex(
                new NodeRegistry(List.of(scanRoot(FIXTURE_PACKAGE))), null);
        assertFalse(index.search("color").isEmpty());
        assertEquals("", index.all().get(0).libraryName());
    }

    // --- helpers ---------------------------------------------------------------

    private static NodeRegistry.ScanRoot scanRoot(String packageName) {
        return new NodeRegistry.ScanRoot(packageName, LOADER, LIBRARY_ID, "", null);
    }

    private static NodeSearchIndex index() {
        return new NodeSearchIndex(new NodeRegistry(List.of(scanRoot(FIXTURE_PACKAGE))),
                id -> LIBRARY_ID.equals(id) ? "Fixture Library" : "");
    }

    private static String top(String query) {
        List<SearchResult> results = index().search(query);
        assertFalse(results.isEmpty(), "expected a match for \"" + query + "\"");
        return results.get(0).node().displayName();
    }

    private static List<String> displayNames(List<SearchResult> results) {
        return results.stream().map(result -> result.node().displayName()).sorted().toList();
    }

    private static NodeKind kindOf(String displayName) {
        NodeDescriptor node = index().all().stream()
                .filter(candidate -> candidate.displayName().equals(displayName))
                .findFirst()
                .orElse(null);
        assertNotNull(node, "no fixture node named " + displayName);
        return node.kind();
    }

    @Test
    void anUntaggedNodeHasANullKind() {
        assertNull(kindOf("UntaggedFixtureNode"));
    }
}
