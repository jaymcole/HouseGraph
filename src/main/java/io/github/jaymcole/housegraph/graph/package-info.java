/**
 * The execution engine and node model — the headless core of HouseGraph.
 * <p>
 * {@link io.github.jaymcole.housegraph.graph.NodeGraph} owns the nodes and their
 * connections and drives execution (data pulled via {@code resolve}, control pushed
 * via {@code execute}). {@link io.github.jaymcole.housegraph.graph.BaseNode} is the
 * base every node extends; {@link io.github.jaymcole.housegraph.graph.NodeVariable}
 * and {@link io.github.jaymcole.housegraph.graph.Edge} carry typed data, while
 * {@link io.github.jaymcole.housegraph.graph.FlowPort} and
 * {@link io.github.jaymcole.housegraph.graph.FlowEdge} carry control flow.
 * {@link io.github.jaymcole.housegraph.graph.NodeRegistry} discovers node classes on
 * the classpath.
 * <p>
 * This package never imports JavaFX; UI notifications go through an injected
 * callback executor and {@link io.github.jaymcole.housegraph.graph.GraphExecutionListener}.
 * See {@code docs/architecture/graph-engine.md} and {@code nodes.md}.
 */
package io.github.jaymcole.housegraph.graph;
