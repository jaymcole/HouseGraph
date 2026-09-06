package io.github.jaymcole.housegraph.loader;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.saveformat.ClipboardNode;

/**
 * Notified as {@link GraphLoader} builds each node, <em>before</em> that node joins the graph.
 * <p>
 * The timing is the contract. {@code NodeGraph.addNode} fires {@code onActivated()}, and a node
 * whose view carries part of its state expects that view to exist by then — a host that builds
 * views does it from here, so construction still precedes activation exactly as it did when the
 * canvas ran the whole load itself. A headless load passes no listener.
 */
@FunctionalInterface
public interface GraphLoadListener {

    /**
     * @param entry the snapshot entry the node was built from — its captured position, for a host
     *              that places the node somewhere
     * @param node  the node the factory returned; never null (an entry the factory could not build
     *              is not reported)
     */
    void nodeBuilt(ClipboardNode entry, BaseNode node);
}
