package io.github.jaymcole.housegraph.search;

import io.github.jaymcole.housegraph.annotations.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryParserTest {

    @Test
    void bareWordsBecomeFreeText() {
        SearchQuery query = QueryParser.parse("repeating trigger");
        assertEquals("repeating trigger", query.text());
        assertFalse(query.hasFacets(), "nothing here filters");
    }

    @Test
    void aBlankQueryMatchesEverything() {
        assertTrue(QueryParser.parse("").isEmpty());
        assertTrue(QueryParser.parse(null).isEmpty());
        assertTrue(QueryParser.parse("   ").isEmpty());
    }

    @Test
    void kindFacetIsParsedCaseInsensitively() {
        assertEquals(Set.of(NodeKind.CONTROL), QueryParser.parse("kind:control").kinds());
        assertEquals(Set.of(NodeKind.CONTROL), QueryParser.parse("KIND:Control").kinds());
        assertEquals(Set.of(NodeKind.ACTION), QueryParser.parse("is:action").kinds(),
                "\"is:\" is an alias for \"kind:\"");
    }

    @Test
    void aFacetIsStrippedFromTheRankingText() {
        SearchQuery query = QueryParser.parse("kind:control repeating");
        assertEquals("repeating", query.text(), "a recognised facet filters; it must not also rank");
        assertEquals(Set.of(NodeKind.CONTROL), query.kinds());
    }

    @Test
    void aLeadingMinusNegatesAFacet() {
        SearchQuery query = QueryParser.parse("-kind:data -lib:core");
        assertEquals(Set.of(NodeKind.DATA), query.excludedKinds());
        assertEquals(Set.of("core"), query.excludedLibraries());
        assertTrue(query.kinds().isEmpty());
    }

    @Test
    void repeatingAKeyWidensIt() {
        assertEquals(Set.of(NodeKind.CONTROL, NodeKind.ACTION),
                QueryParser.parse("kind:control kind:action").kinds(),
                "two values for one key are alternatives, not a contradiction");
    }

    @Test
    void libraryCategoryAndTagFacetsAllParse() {
        SearchQuery query = QueryParser.parse("lib:discord cat:math tag:plus");
        assertEquals(Set.of("discord"), query.libraries());
        assertEquals(List.of("math"), query.categories());
        assertEquals(Set.of("plus"), query.tags());
    }

    @Test
    void facetAliasesResolveToTheSameFacet() {
        assertEquals(Set.of("discord"), QueryParser.parse("plugin:discord").libraries());
        assertEquals(List.of("math"), QueryParser.parse("category:math").categories());
        assertEquals(Set.of("plus"), QueryParser.parse("kw:plus").tags());
        assertEquals(Set.of("plus"), QueryParser.parse("keyword:plus").tags());
    }

    @Test
    void quotesHoldAPhraseTogether() {
        assertEquals("image viewer", QueryParser.parse("\"image viewer\"").text());
    }

    @Test
    void anUnclosedQuoteRunsToTheEndRatherThanFailing() {
        assertEquals("image viewer", QueryParser.parse("\"image viewer").text(),
                "half-typed input is the normal state of a search box, not an error");
    }

    @Test
    void anUnrecognisedKeyBecomesFreeTextAndIsReported() {
        SearchQuery query = QueryParser.parse("in:Float");
        assertEquals(List.of("in:Float"), query.unrecognisedFacets());
        assertEquals("in float", query.text(),
                "a user reaching for a facet we do not implement should get results, not a blank list");
        assertFalse(query.hasFacets());
    }

    @Test
    void anUnparseableKindValueBecomesFreeTextRatherThanFilteringEverythingOut() {
        SearchQuery query = QueryParser.parse("kind:sideways");
        assertTrue(query.kinds().isEmpty());
        assertEquals(List.of("kind:sideways"), query.unrecognisedFacets());
        assertEquals("sideways", query.text());
    }

    @Test
    void aColonWithNothingAroundItIsJustText() {
        assertEquals("http localhost 8080", QueryParser.parse(":http localhost:8080").text(),
                "a leading colon names no key, and a trailing one names no value");
    }
}
