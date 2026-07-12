package io.github.jaymcole.housegraph.graph.nodes.debug;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;

import java.util.Random;

/**
 * A deliberate footgun for exercising load resiliency: every instance builds a <em>random</em>
 * number of data and flow anchors, chosen once when the node is first configured and persisted
 * nowhere. Because {@code GraphFileIO} references anchors by positional index and this node saves
 * nothing about its own shape, a fresh instance created on load re-rolls a different anchor count -
 * so edges wired to a high-index anchor before the save frequently point past the anchors that
 * exist after the load, which is exactly the "endpoint no longer resolves" case the load path must
 * survive by dropping only that edge (see {@code GraphCanvas.place} / {@code GraphFileIO}).
 * <p>
 * Two properties fall out of this by construction:
 * <ul>
 *   <li><b>A save is always self-consistent.</b> You can only draw an edge to an anchor that
 *       currently exists, so at save time every edge on this node is valid - nothing extra is
 *       needed to guarantee that.</li>
 *   <li><b>A load usually invalidates some of those edges,</b> and reliably so if you wire the
 *       higher-index anchors: with up to {@link #MAX_ANCHORS} anchors, a reload lands fewer than a
 *       given wired index with high probability. It is <em>not</em> a mathematical certainty on any
 *       single reload (a reload could re-roll enough anchors), so wire several high anchors when you
 *       want a near-guaranteed break, and reload a couple of times.</li>
 * </ul>
 * This is a manual testing tool, not a real node - dropping it into a real graph will silently eat
 * edges across a save/load. It needed no engine or framework support: it is an ordinary
 * {@link BaseNode} whose {@code configure*} hooks happen to add a random number of ports.
 */
@Display.Name("Chaos Anchors (debug)")
public class ChaosAnchorNode extends BaseNode {

    /** Upper bound on each anchor group's random size; a wider range makes reload breakage more likely. */
    private static final int MAX_ANCHORS = 8;

    // One shared source of randomness per instance. The counts are drawn lazily inside the
    // configure hooks (each runs once per instance - see BaseNode.ensureConfigured), so a node's
    // shape is stable for its whole life on the canvas and only re-rolls when a *new* instance is
    // built, i.e. on load. Nothing here is written to saveState, which is what keeps the reloaded
    // shape independent of the saved one.
    private final Random random = new Random();

    @Override
    public void process(ProcessContext ctx) {
        // Intentionally does nothing - this node exists to reshape its anchors, not to compute.
    }

    @Override
    public void configureInputs() {
        int count = 1 + random.nextInt(MAX_ANCHORS);
        for (int i = 0; i < count; i++) {
            addInput(new NodeVariable<>("In " + i, Float.class));
        }
    }

    @Override
    public void configureOutputs() {
        int count = 1 + random.nextInt(MAX_ANCHORS);
        for (int i = 0; i < count; i++) {
            addOutput(new NodeVariable<>("Out " + i, Float.class));
        }
    }

    @Override
    public void configureFlowInputs() {
        int count = 1 + random.nextInt(MAX_ANCHORS);
        for (int i = 0; i < count; i++) {
            addFlowInput(new FlowPort("In " + i, FlowPort.Direction.IN));
        }
    }

    @Override
    public void configureFlowOutputs() {
        int count = 1 + random.nextInt(MAX_ANCHORS);
        for (int i = 0; i < count; i++) {
            addFlowOutput(new FlowPort("Out " + i, FlowPort.Direction.OUT));
        }
    }
}
