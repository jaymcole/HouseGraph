package io.github.jaymcole.housegraph.graph.nodes.debug;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chaos node's whole job is to reshape its anchors on every load. These check the two
 * properties the resiliency test relies on: a fresh instance re-rolls a different anchor count
 * (so a reloaded copy differs from the saved one), and it persists no shape (so nothing pins that
 * count back to the saved value).
 */
class ChaosAnchorNodeTest {

    @Test
    void freshInstancesReRollTheirAnchorCounts() {
        // If the shape were fixed, every instance would report the same output count and this set
        // would have size 1. Across many instances we expect several distinct counts. (Astronomically
        // unlikely to flake: all-equal across 50 draws of 1..8 has probability (1/8)^49.)
        Set<Integer> distinctOutputCounts = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            distinctOutputCounts.add(new ChaosAnchorNode().getOutputs().size());
        }
        assertTrue(distinctOutputCounts.size() > 1,
                "a fresh instance should re-roll its anchor count, so a reload differs from the save");
    }

    @Test
    void persistsNoShapeState() {
        // Saving nothing is what keeps the reloaded shape independent of the saved one - if this node
        // pinned its count in saveState it would round-trip stably and never exercise the resiliency.
        assertTrue(new ChaosAnchorNode().saveState().isEmpty(),
                "the chaos node must not persist its anchor shape");
    }
}
