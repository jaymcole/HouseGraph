package io.github.jaymcole.housegraph.catalog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Compares two parsed save-file roots and reports what differs, without instantiating a single
 * node or touching a canvas — the engine behind {@code housegraph diff}, a dry run for a harness
 * deciding whether to write a proposed graph over the one currently on disk.
 *
 * <h2>Node identity is positional, edge identity is by content</h2>
 * The save format itself addresses a node only by its index in the root {@code nodes} array (an
 * edge's {@code sourceNode}/{@code targetNode} is that index), so {@code nodes} is compared
 * index-by-index: index 3 in {@code current} is compared against index 3 in {@code proposed},
 * field by field, and a length mismatch reports the extra trailing indices as added or removed.
 * <b>Inserting or removing a node anywhere but the end shifts every later index</b> and is
 * reported as a cascade of per-field modifications rather than one clean move — a limitation of
 * the format's own addressing, not of this comparison.
 * <p>
 * {@code dataEdges}, {@code flowEdges}, {@code plugins} and {@code modules} carry no such positional meaning —
 * nothing else references an edge or a plugin row by its index in those arrays — so reordering
 * one of those arrays alone is reported as no change. Edges are matched by full content
 * (including waypoints); a changed edge is reported as one removed and one added rather than a
 * modification. Plugin rows are matched by {@code id}, so a version or repository bump on an
 * already-depended-on library shows as a field-level change rather than a swap.
 *
 * <h2>Number formatting is not a change</h2>
 * A proposed file need not match {@code org.json}'s own number formatting: {@code 10} and
 * {@code 10.0} compare equal, so a hand- or agent-written file that spells a coordinate
 * differently from what {@link io.github.jaymcole.housegraph.saveformat.GraphFileIO} would have
 * written is not reported as a change.
 */
public final class GraphDiff {

    private GraphDiff() {
    }

    /** One difference between {@code current} and {@code proposed}, addressed by an RFC 6901 JSON Pointer. */
    public enum ChangeType { ADDED, REMOVED, MODIFIED }

    /**
     * @param type    what kind of difference this is
     * @param pointer an RFC 6901 JSON Pointer into the file the change is easiest to locate in —
     *                {@code proposed} for an {@link ChangeType#ADDED} or {@link ChangeType#MODIFIED}
     *                entry, {@code current} for a {@link ChangeType#REMOVED} one
     * @param before  the value in {@code current}, or null for {@link ChangeType#ADDED}
     * @param after   the value in {@code proposed}, or null for {@link ChangeType#REMOVED}
     */
    public record Change(ChangeType type, String pointer, Object before, Object after) {
        static Change added(String pointer, Object after) {
            return new Change(ChangeType.ADDED, pointer, null, after);
        }

        static Change removed(String pointer, Object before) {
            return new Change(ChangeType.REMOVED, pointer, before, null);
        }

        static Change modified(String pointer, Object before, Object after) {
            return new Change(ChangeType.MODIFIED, pointer, before, after);
        }
    }

    /** The full set of differences, in a stable order (root keys visited alphabetically). */
    public record Report(List<Change> changes) {
        public Report {
            changes = List.copyOf(changes);
        }

        public boolean isUnchanged() {
            return changes.isEmpty();
        }
    }

    /**
     * Compares two save-file roots.
     *
     * @param current  the graph currently on disk (or its would-be predecessor)
     * @param proposed the candidate that would replace it
     * @return every difference found; empty when the two are equivalent
     */
    public static Report compare(JSONObject current, JSONObject proposed) {
        List<Change> changes = new ArrayList<>();
        for (String key : new TreeSet<>(union(current.keySet(), proposed.keySet()))) {
            String pointer = "/" + escape(key);
            switch (key) {
                case "dataEdges", "flowEdges" ->
                        diffBag(changes, pointer, current.optJSONArray(key), proposed.optJSONArray(key),
                                GraphDiff::canonicalize);
                case "plugins", "modules" ->
                        diffBag(changes, pointer, current.optJSONArray(key), proposed.optJSONArray(key),
                                row -> row instanceof JSONObject json ? json.optString("id", "") : canonicalize(row));
                default -> diffValue(changes, pointer,
                        current.has(key) ? current.get(key) : null,
                        proposed.has(key) ? proposed.get(key) : null);
            }
        }
        return new Report(changes);
    }

    private static void diffValue(List<Change> changes, String pointer, Object a, Object b) {
        if (a == null && b == null) {
            return;
        }
        if (a == null) {
            changes.add(Change.added(pointer, b));
            return;
        }
        if (b == null) {
            changes.add(Change.removed(pointer, a));
            return;
        }
        if (a instanceof JSONObject objectA && b instanceof JSONObject objectB) {
            diffObject(changes, pointer, objectA, objectB);
        } else if (a instanceof JSONArray arrayA && b instanceof JSONArray arrayB) {
            diffArray(changes, pointer, arrayA, arrayB);
        } else if (!scalarEquals(a, b)) {
            changes.add(Change.modified(pointer, a, b));
        }
    }

    private static void diffObject(List<Change> changes, String pointer, JSONObject a, JSONObject b) {
        for (String key : new TreeSet<>(union(a.keySet(), b.keySet()))) {
            diffValue(changes, pointer + "/" + escape(key), a.has(key) ? a.get(key) : null, b.has(key) ? b.get(key) : null);
        }
    }

    /** Index-aligned array comparison — what {@code nodes}, a node's {@code inputs}/{@code outputs}, and {@code camera} use. */
    private static void diffArray(List<Change> changes, String pointer, JSONArray a, JSONArray b) {
        int length = Math.max(a.length(), b.length());
        for (int i = 0; i < length; i++) {
            diffValue(changes, pointer + "/" + i, i < a.length() ? a.get(i) : null, i < b.length() ? b.get(i) : null);
        }
    }

    /**
     * Order-independent array comparison: each element of {@code a} is matched to an unused element
     * of {@code b} with the same {@code keyOf}, greedily and at most once. An unmatched element of
     * {@code a} is removed; an unmatched element of {@code b} is added. A matched pair is diffed too,
     * so a key that identifies a row without covering its whole content (a plugin's {@code id}) still
     * surfaces field-level changes.
     */
    private static void diffBag(List<Change> changes, String pointer, JSONArray a, JSONArray b, Function<Object, String> keyOf) {
        List<Object> currentItems = toList(a);
        List<Object> proposedItems = toList(b);

        Map<String, List<Integer>> proposedByKey = new LinkedHashMap<>();
        for (int i = 0; i < proposedItems.size(); i++) {
            proposedByKey.computeIfAbsent(keyOf.apply(proposedItems.get(i)), k -> new ArrayList<>()).add(i);
        }

        boolean[] matched = new boolean[proposedItems.size()];
        for (int i = 0; i < currentItems.size(); i++) {
            Object item = currentItems.get(i);
            List<Integer> candidates = proposedByKey.get(keyOf.apply(item));
            Integer match = firstUnmatched(candidates, matched);
            if (match == null) {
                changes.add(Change.removed(pointer + "/" + i, item));
            } else {
                matched[match] = true;
                diffValue(changes, pointer + "/" + match, item, proposedItems.get(match));
            }
        }
        for (int j = 0; j < proposedItems.size(); j++) {
            if (!matched[j]) {
                changes.add(Change.added(pointer + "/" + j, proposedItems.get(j)));
            }
        }
    }

    private static Integer firstUnmatched(List<Integer> candidates, boolean[] matched) {
        if (candidates == null) {
            return null;
        }
        for (int candidate : candidates) {
            if (!matched[candidate]) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Object> toList(JSONArray array) {
        List<Object> items = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                items.add(array.get(i));
            }
        }
        return items;
    }

    private static java.util.Set<String> union(java.util.Set<String> a, java.util.Set<String> b) {
        java.util.Set<String> all = new java.util.LinkedHashSet<>(a);
        all.addAll(b);
        return all;
    }

    /** {@code true} when two scalars represent the same value, treating any two numbers by their {@code double} value. */
    private static boolean scalarEquals(Object a, Object b) {
        if (a instanceof Number numberA && b instanceof Number numberB) {
            return numberA.doubleValue() == numberB.doubleValue();
        }
        return Objects.equals(a, b);
    }

    /** A content string for bag matching: sorted object keys at every level, numbers normalized, so formatting differences don't split an identical edge into a remove+add. */
    private static String canonicalize(Object value) {
        if (value instanceof JSONObject json) {
            StringBuilder text = new StringBuilder("{");
            boolean first = true;
            for (String key : new TreeSet<>(json.keySet())) {
                if (!first) {
                    text.append(',');
                }
                first = false;
                text.append(JSONObject.quote(key)).append(':').append(canonicalize(json.get(key)));
            }
            return text.append('}').toString();
        }
        if (value instanceof JSONArray array) {
            StringBuilder text = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) {
                    text.append(',');
                }
                text.append(canonicalize(array.get(i)));
            }
            return text.append(']').toString();
        }
        if (value instanceof Number number) {
            return String.valueOf(number.doubleValue());
        }
        return String.valueOf(value);
    }

    /** JSON Pointer escaping (RFC 6901): {@code ~} and {@code /} inside a key would otherwise be read as pointer syntax. */
    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
