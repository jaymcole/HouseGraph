package io.github.jaymcole.housegraph.modules;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * One port on the face of a module: what a graph's boundary marker looks like from <em>outside</em>
 * the graph.
 *
 * <h2>The direction is the marker's, inverted</h2>
 * A boundary marker's own port already points the opposite way from its name — a {@code
 * ModuleInputNode} carries a data <em>output</em>, because a value handed into the graph surfaces
 * there. Read from outside, that inverts back: the module has a data port named by the marker that
 * a consumer feeds, so the direction recorded here is {@link Direction#IN}. Entry and Exit invert
 * the same way on the flow plane.
 * <p>
 * Everything downstream — the ports a {@code ModuleNode} builds, what a save file records —
 * uses <em>this</em> orientation, the consumer's. The interior orientation exists only inside the
 * module file.
 *
 * <h2>The name is the marker's, verbatim</h2>
 * {@code ModuleBoundaryNode} stores its declared name exactly as typed, because save files bind
 * edge endpoints by port name. That name arrives here untouched for the same reason: normalising
 * it would silently rename the consumer's ports and drop every edge bound to the old spelling.
 *
 * @param name      the declaring marker's name, verbatim; blank when it declared none
 * @param kind      whether this port carries a value or a control signal
 * @param direction which way it points, as seen from a consumer
 * @param typeName  the declared type's fully-qualified class name for a {@link Kind#DATA} port —
 *                  as declared, not as resolved here — and empty for a {@link Kind#FLOW} one
 */
public record ModulePort(String name, Kind kind, Direction direction, String typeName) {

    /** Whether a port carries a value ({@code NodeVariable}) or a control signal ({@code FlowPort}). */
    public enum Kind {
        DATA,
        FLOW
    }

    /** Which way a port points, <b>as seen from the consuming graph</b>, never from inside the module. */
    public enum Direction {
        IN,
        OUT
    }

    public ModulePort {
        name = name == null ? "" : name;
        typeName = typeName == null ? "" : typeName;
    }

    /**
     * Encodes a port list as the single string a node's {@code saveState()} can hold, as a JSON
     * array of four-element arrays: {@code [["data","in","Temperature","java.lang.Float"], …]}.
     *
     * <p>Positional arrays rather than the {@code "name:type, …"} text {@code ObjectDecomposerNode}
     * uses, because a declared name is stored <b>verbatim</b> and may contain any character a user
     * can type — a comma or a colon included — which a delimiter-joined form cannot survive. Arrays
     * also preserve their order exactly, and port order is load-bearing: a save file falls back to
     * a positional reference for an endpoint whose name is blank or ambiguous.
     *
     * @param ports the ports to encode, in port order
     * @return the encoded shape, parseable by {@link #decode(String)}
     */
    public static String encode(List<ModulePort> ports) {
        JSONArray array = new JSONArray();
        for (ModulePort port : ports) {
            array.put(new JSONArray()
                    .put(port.kind().name().toLowerCase(java.util.Locale.ROOT))
                    .put(port.direction().name().toLowerCase(java.util.Locale.ROOT))
                    .put(port.name())
                    .put(port.typeName()));
        }
        return array.toString();
    }

    /**
     * Reads back what {@link #encode(List)} wrote. An entry that is not a four-element array, or
     * that names a kind or direction this build does not know, is dropped rather than failing the
     * whole shape: a port that cannot be rebuilt costs the edges bound to it, while a throw here
     * would cost the entire node.
     *
     * @param encoded the encoded shape, or null/blank for none
     * @return the ports, in the order they were encoded; empty when there were none
     */
    public static List<ModulePort> decode(String encoded) {
        List<ModulePort> ports = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) {
            return ports;
        }
        JSONArray array;
        try {
            array = new JSONArray(encoded);
        } catch (RuntimeException e) {
            return ports;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONArray entry = array.optJSONArray(i);
            if (entry == null || entry.length() < 4) {
                continue;
            }
            Kind kind = parseKind(entry.optString(0, ""));
            Direction direction = parseDirection(entry.optString(1, ""));
            if (kind == null || direction == null) {
                continue;
            }
            ports.add(new ModulePort(entry.optString(2, ""), kind, direction, entry.optString(3, "")));
        }
        return ports;
    }

    private static Kind parseKind(String text) {
        for (Kind kind : Kind.values()) {
            if (kind.name().equalsIgnoreCase(text)) {
                return kind;
            }
        }
        return null;
    }

    private static Direction parseDirection(String text) {
        for (Direction direction : Direction.values()) {
            if (direction.name().equalsIgnoreCase(text)) {
                return direction;
            }
        }
        return null;
    }
}
