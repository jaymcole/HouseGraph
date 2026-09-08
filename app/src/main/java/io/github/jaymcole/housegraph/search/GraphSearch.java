package io.github.jaymcole.housegraph.search;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.sdk.ValueEditors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Find-in-graph: which of the nodes already on the canvas match what the user typed.
 *
 * <h2>Why this is not {@link NodeSearchIndex}</h2>
 * That index answers "which node <em>type</em> did I mean?" and so it ranks, tolerates typos and
 * returns the closest thing to a half-remembered name. This answers "where in <em>this</em> graph
 * is the thing I am looking at?", and the two want opposite behaviour. A find highlights a set
 * rather than picking one winner, so there is nothing for a ranking to order; and a fuzzy find
 * lights up nodes the user did not type, on a canvas where every extra yellow border is a place
 * they have to go and look. So this filters, on a literal containment test, and a query that
 * matches nothing highlights nothing.
 *
 * <h2>What is matched</h2>
 * Everything the node itself can be asked for, which — unlike the type index — includes its ports,
 * because these nodes are already built and their {@code configureInputs()} has already run:
 * <ul>
 *   <li>the display name and the simple class name;</li>
 *   <li>every data port's name and every named flow port's name;</li>
 *   <li>the text of every value the user typed in, rendered through the same
 *       {@link ValueEditors} formatter the inline field shows it with.</li>
 * </ul>
 * The values are what make the feature worth having: a graph with thirty {@code Add}s tells them
 * apart by nothing else, and the address or channel name someone is hunting for lives in a field,
 * not in a node's name.
 *
 * <h2>Values, and what is never read</h2>
 * A value is matched only when {@link NodeVariable#isPersistentValue()} — manually authored,
 * non-secret, non-transient — and its type has a registered editor. That gate is deliberately the
 * same one persistence uses: a secret is not searchable, so no query can confirm one character of
 * it, and a computed runtime value is not searchable either, so what a find highlights does not
 * depend on whether the graph happens to be running.
 *
 * <h2>Matching</h2>
 * One containment test against a per-node haystack, both sides normalised by {@link SearchText}.
 * Each field is appended twice — normalised whole, then as its camelCase tokens — so
 * {@code addno} and {@code add node} both find {@code AddNode} without needing two passes or a
 * second notion of a word boundary. Fields are kept apart by a separator no query can contain, so
 * a match is always wholly inside one of them.
 *
 * <p>Nothing is cached. The haystack is rebuilt on every keystroke because the thing it is built
 * from changes under it constantly — a typed value, a rebuilt port list, a bound module — and a
 * cache keyed on none of those would go stale in exactly the case a find is for. It is a handful
 * of short strings per node.
 *
 * <p>No JavaFX: {@code ui/GraphCanvas} owns the find bar and the highlighting, this owns the
 * question of what matches. See {@code docs/engine/node-search.md}.
 */
public final class GraphSearch {

    /**
     * Ends every field in the haystack. A newline because {@link SearchText#normalise} turns every
     * non-alphanumeric character into a space, so no compiled query can hold one.
     */
    private static final char FIELD_SEPARATOR = '\n';

    private GraphSearch() {
    }

    /**
     * A query with its normalisation already done, so a canvas can test every node it holds
     * against one. Immutable, and safe to keep for as long as the find bar shows that text.
     */
    public static final class Query {

        private final String needle;

        private Query(String needle) {
            this.needle = needle;
        }

        /** True when there is nothing to search for, and so nothing to highlight. */
        public boolean isBlank() {
            return needle.isEmpty();
        }

        /**
         * @param node the node to test
         * @return true if this query appears anywhere in the node's searchable text
         */
        public boolean matches(BaseNode node) {
            return !needle.isEmpty() && node != null && haystack(node).contains(needle);
        }
    }

    /**
     * Prepares a raw query string for matching.
     *
     * @param query what the user typed; null or blank yields a query that matches nothing
     * @return the compiled query
     */
    public static Query compile(String query) {
        return new Query(SearchText.normalise(query));
    }

    /**
     * Convenience for a one-shot search over a collection.
     *
     * @param nodes the nodes to test
     * @param query what the user typed
     * @return the matching nodes, in the order given; empty for a blank query
     */
    public static List<BaseNode> matches(Collection<BaseNode> nodes, String query) {
        Query compiled = compile(query);
        List<BaseNode> found = new ArrayList<>();
        if (compiled.isBlank() || nodes == null) {
            return found;
        }
        for (BaseNode node : nodes) {
            if (compiled.matches(node)) {
                found.add(node);
            }
        }
        return found;
    }

    /** Everything about one node that a find may match, normalised and concatenated. */
    private static String haystack(BaseNode node) {
        StringBuilder text = new StringBuilder();
        append(text, node.getName());
        append(text, node.getClass().getSimpleName());
        for (NodeVariable<?> input : node.getInputs()) {
            appendVariable(text, input);
        }
        for (NodeVariable<?> output : node.getOutputs()) {
            appendVariable(text, output);
        }
        for (FlowPort flowPort : node.getFlowInputs()) {
            append(text, flowPort.name);
        }
        for (FlowPort flowPort : node.getFlowOutputs()) {
            append(text, flowPort.name);
        }
        return text.toString();
    }

    private static void appendVariable(StringBuilder text, NodeVariable<?> variable) {
        append(text, variable.name);
        append(text, authoredText(variable));
    }

    /**
     * The text an inline editor would be showing for this variable, or null when there is none to
     * show — which covers a secret, a transient runtime value, a type with no editor, and an
     * unset one.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String authoredText(NodeVariable<?> variable) {
        if (!variable.isPersistentValue()) {
            return null;
        }
        Object value = variable.getValue();
        if (value == null) {
            return null;
        }
        ValueEditors.Editor editor = ValueEditors.editorFor(variable.type);
        if (editor == null) {
            return null;
        }
        try {
            return editor.format(value);
        } catch (RuntimeException e) {
            // A node library supplies its own formatter. One that throws on its own value must
            // cost that node its searchability, not stop the user typing into the find box.
            return null;
        }
    }

    /**
     * Appends one field to the haystack in both of its readings — normalised whole, then split on
     * camelCase — each terminated by {@link #FIELD_SEPARATOR}. Normalisation flattens every
     * non-alphanumeric character, so a compiled query can never contain that separator and a match
     * therefore cannot run across the join between two unrelated fields: a node named "Add" with a
     * port called "Node" is not a hit for "add node".
     */
    private static void append(StringBuilder text, String field) {
        String normalised = SearchText.normalise(field);
        if (normalised.isEmpty()) {
            return;
        }
        text.append(normalised).append(FIELD_SEPARATOR);
        List<String> tokens = SearchText.tokenise(field);
        if (tokens.size() > 1) {
            text.append(String.join(" ", tokens)).append(FIELD_SEPARATOR);
        }
    }
}
