package io.github.jaymcole.housegraph.search;

import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.search.fixture.nodes.alpha.AddFixtureNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeScorerTest {

    @Test
    void exactBeatsPrefixBeatsSubstringBeatsFuzzy() {
        List<IndexedNode> corpus = corpusOf(
                named("Add"),               // exact
                named("Addendum"),          // prefix
                named("Rapid Adder"),       // substring
                named("Adz"));              // fuzzy at best
        NodeScorer scorer = new NodeScorer(corpus);

        double exact = score(scorer, corpus, "add", 0);
        double prefix = score(scorer, corpus, "add", 1);
        double substring = score(scorer, corpus, "add", 2);
        double fuzzy = score(scorer, corpus, "add", 3);

        assertTrue(exact > prefix, "an exact name match must outrank a prefix match");
        assertTrue(prefix > substring, "a prefix match must outrank an interior substring match");
        assertTrue(substring > fuzzy, "any literal match must outrank a fuzzy one, or a user who "
                + "typed a real prefix will not trust the ranking again");
    }

    @Test
    void aMisspellingStillMatchesTheNodeItMeant() {
        List<IndexedNode> corpus = corpusOf(named("Color Picker"), named("Constant Float"));
        NodeScorer scorer = new NodeScorer(corpus);

        assertTrue(score(scorer, corpus, "colr", 0) >= NodeScorer.MINIMUM_SCORE,
                "\"colr\" is a plain misspelling of \"color\" and must still find it");
    }

    @Test
    void sharingOnlyTheFirstLettersIsNotAMatch() {
        List<IndexedNode> corpus = corpusOf(named("Constant Float"));
        NodeScorer scorer = new NodeScorer(corpus);

        assertEquals(0, score(scorer, corpus, "colr", 0), 1e-9,
                "\"colr\" and \"constant\" overlap only in padding trigrams; scoring that as a "
                        + "partial match is what fills a result list with noise");
    }

    @Test
    void aKeywordFindsANodeWhoseNameLacksTheTerm() {
        List<IndexedNode> corpus = corpusOf(
                descriptor("Add Numbers", "AddFixtureNode", "alpha", "", List.of("plus", "sum"), NodeKind.DATA),
                named("Subtract"));
        NodeScorer scorer = new NodeScorer(corpus);

        NodeScorer.Scored scored = scoreOf(scorer, corpus, "plus", 0);
        assertTrue(scored.score() >= NodeScorer.MINIMUM_SCORE, "a keyword synonym must surface the node");
        assertEquals(SearchResult.SearchField.KEYWORDS, scored.field(),
                "and the result should be able to say it was the keywords that matched");
    }

    @Test
    void aKeywordIsMatchedWholeRatherThanAgainstTheJoinedList() {
        List<IndexedNode> corpus = corpusOf(
                descriptor("Alpha", "Alpha", "alpha", "", List.of("plus", "sum", "arithmetic"), null),
                descriptor("Beta", "Beta", "alpha", "", List.of("summary"), null));
        NodeScorer scorer = new NodeScorer(corpus);

        assertTrue(score(scorer, corpus, "sum", 0) > score(scorer, corpus, "sum", 1),
                "\"sum\" exactly equals one of Alpha's keywords; against Beta it is only a prefix");
    }

    @Test
    void aTermEveryNodeSharesBarelyMovesTheRanking() {
        // "node" appears in every simple name here, exactly as it does in the real library.
        List<IndexedNode> corpus = corpusOf(
                descriptor("Alpha", "AlphaNode", "alpha", "", List.of(), null),
                descriptor("Beta", "BetaNode", "alpha", "", List.of(), null),
                descriptor("Gamma", "GammaNode", "alpha", "", List.of(), null));
        NodeScorer scorer = new NodeScorer(corpus);

        double first = score(scorer, corpus, "node", 0);
        double second = score(scorer, corpus, "node", 1);
        assertEquals(first, second, 1e-9,
                "a term shared by the whole corpus cannot distinguish anything, so document "
                        + "frequency should drive its contribution to nothing — no stopword list needed");
    }

    @Test
    void aMultiWordQueryRanksAFullMatchAboveAPartialOne() {
        List<IndexedNode> corpus = corpusOf(named("List to String"), named("Float to String"));
        NodeScorer scorer = new NodeScorer(corpus);

        assertTrue(score(scorer, corpus, "list to string", 0) > score(scorer, corpus, "list to string", 1),
                "both nodes share \"to string\", so the one that also matches \"list\" must win");
    }

    @Test
    void aQueryWhoseOtherWordsAreIrrelevantStillFindsTheMatch() {
        List<IndexedNode> corpus = corpusOf(named("Color Picker"), named("Add"));
        NodeScorer scorer = new NodeScorer(corpus);

        assertTrue(score(scorer, corpus, "in float color", 0) >= NodeScorer.MINIMUM_SCORE,
                "reading the query only as one phrase would spread its trigrams across words "
                        + "\"Color Picker\" does not have and lose a match its own name earns outright");
        assertEquals(0, score(scorer, corpus, "in float color", 1), 1e-9,
                "and the node that matches none of the words still scores nothing");
    }

    // --- helpers ---------------------------------------------------------------

    private static NodeDescriptor named(String displayName) {
        return descriptor(displayName, displayName.replace(" ", "") + "Node", "alpha", "", List.of(), null);
    }

    private static NodeDescriptor descriptor(String displayName, String simpleName, String category,
                                             String description, List<String> keywords, NodeKind kind) {
        Class<? extends BaseNode> nodeClass = AddFixtureNode.class;
        return new NodeDescriptor(nodeClass, simpleName, displayName, simpleName, category,
                "core", "", description, keywords, kind);
    }

    private static List<IndexedNode> corpusOf(NodeDescriptor... descriptors) {
        return List.of(descriptors).stream().map(IndexedNode::of).toList();
    }

    private static double score(NodeScorer scorer, List<IndexedNode> corpus, String query, int index) {
        return scoreOf(scorer, corpus, query, index).score();
    }

    private static NodeScorer.Scored scoreOf(NodeScorer scorer, List<IndexedNode> corpus, String query, int index) {
        return scorer.score(NodeScorer.QueryText.of(SearchText.normalise(query)), corpus.get(index));
    }
}
