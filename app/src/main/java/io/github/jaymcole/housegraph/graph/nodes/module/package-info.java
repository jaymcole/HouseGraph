/**
 * Module boundary markers: the four node types that declare where values and control cross
 * the edge of a graph, so the graph can be described by an interface rather than only by its
 * contents.
 * <p>
 * A boundary node declares a <em>name</em> (all four) and a <em>type</em> (the data pair), and
 * carries exactly one port — the inside face of that declaration. The ports are inverted
 * relative to the declaration: a Module Input hands a value <em>into</em> the graph, so its own
 * port is a data <em>output</em>; a Module Output takes one back, so its port is a data
 * <em>input</em>. Entry and Exit are the same inversion on the flow plane.
 * <p>
 * Flow and data get separate node types rather than one type with a mode switch, because they
 * are separate concepts everywhere else in the engine — a {@code FlowPort} carries no value and
 * a {@code NodeVariable} carries no control.
 */
package io.github.jaymcole.housegraph.graph.nodes.module;
