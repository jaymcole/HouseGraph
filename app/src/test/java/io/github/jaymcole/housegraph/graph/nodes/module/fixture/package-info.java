/**
 * Nodes that exist only to be put <em>inside</em> a module in a test: something that blocks until it
 * is told not to, and something that reports its own lifecycle. Both answer questions a module's
 * data ports cannot — whether cancellation crossed the boundary, and whether removing the consuming
 * node tore the module down.
 */
package io.github.jaymcole.housegraph.graph.nodes.module.fixture;
