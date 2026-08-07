package io.github.jaymcole.housegraph.graph;

import java.util.UUID;

/**
 * A control-flow anchor on a node: a named, valueless execution-order signal.
 * <p>
 * The deliberate counterpart to {@link NodeVariable}. A NodeVariable holds a typed
 * <em>data</em> value that flows along an {@link Edge}; a FlowPort holds no value at
 * all — it only marks a point where control enters ({@link Direction#IN}) or leaves
 * ({@link Direction#OUT}) a node along a {@link FlowEdge}. Keeping the two as separate
 * concepts (rather than folding flow into NodeVariable) is what lets each stay honest:
 * no dead value/type machinery on flow ports, no control-only special-casing on data.
 * <p>
 * A node can expose several OUT ports and choose at runtime which to fire (see
 * {@link BaseNode#activate}), which is what makes branch/decider nodes possible. The
 * {@code name} is what the UI labels the port with; a blank name renders as a bare
 * anchor with no label, which is the right look for a node that has just one.
 */
public class FlowPort {

    public enum Direction {
        IN,
        OUT
    }

    public final String name;
    public final String id;
    public final Direction direction;

    public FlowPort(String name, Direction direction, String id) {
        this.name = name;
        this.direction = direction;
        this.id = id;
    }

    public FlowPort(String name, Direction direction) {
        this(name, direction, UUID.randomUUID().toString());
    }
}
