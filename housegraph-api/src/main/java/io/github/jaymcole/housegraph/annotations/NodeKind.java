package io.github.jaymcole.housegraph.annotations;

/**
 * The semantic role a node plays in a graph, declared with {@link Node.Kind} and used to filter
 * and group search results.
 * <p>
 * This is <em>orthogonal to a node's category</em>. A category is the node's folder, and it
 * describes where the node sits in the Add-Node menu; a kind describes what the node is for, and
 * it cuts across folders. An out-of-tree Discord library's nodes all share one category but split
 * across {@link #ACTION} (send a message) and {@link #RESOURCE} (the bot connection itself).
 * <p>
 * The four values are not invented here — {@code docs/nodes/guidelines.md} already states the
 * control-versus-action rule and names the resource exception to it. This enum only makes what
 * that page says machine-readable.
 * <p>
 * A node that declares no kind has none: it is simply absent from kind-filtered results, rather
 * than being guessed into a bucket. A wrong kind is worse than no kind, because a user who
 * filters by one will never see the node at all and has no way to tell why.
 *
 * @see Node.Kind
 */
public enum NodeKind {

    /**
     * Shapes <em>when</em> and <em>how often</em> flow moves: a trigger, a timer, a branch, a
     * loop, a join. Its job is deciding whether something downstream runs, not doing that
     * something.
     */
    CONTROL,

    /**
     * <em>Does</em> something: calls an API, reads a sensor, writes a file, shows a value. Its
     * flow outputs report that it ran and at most which of a few outcomes happened for that one
     * invocation — not points on a schedule it manages itself.
     */
    ACTION,

    /**
     * Owns a real connection lifecycle — a bot, a web server, a camera. Start/Stop and the live
     * state genuinely belong to the node because the connection <em>is</em> what it manages. This
     * is the named exception to keeping control and action separate.
     */
    RESOURCE,

    /**
     * Produces or transforms a value with no side effect and, usually, no flow ports at all: a
     * constant, an arithmetic operation, a converter, a decomposer. Pulled on demand rather than
     * pushed.
     */
    DATA
}
