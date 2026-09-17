package io.github.jaymcole.housegraph.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The headless half of the port-description tooltip: {@link NodeVariable#describedAs(String)} is
 * what a node author writes, and {@link NodeVariable#hasDescription()} is what the UI asks before
 * installing a tooltip at all. A port with nothing to say gets no tooltip rather than one
 * restating its name, so "nothing to say" has to include blank text, not just null.
 */
class VariableDescriptionTest {

    @Test
    void aVariableHasNoDescriptionByDefault() {
        NodeVariable<Float> variable = new NodeVariable<>("Temperature", Float.class, true);

        assertNull(variable.getDescription());
        assertFalse(variable.hasDescription());
    }

    @Test
    void describedAsRecordsTheTextAndChains() {
        NodeVariable<Float> variable = new NodeVariable<>("Temperature", Float.class, true);

        NodeVariable<Float> returned = variable.describedAs("0 is literal, 1 is inventive.");

        assertSame(variable, returned);
        assertEquals("0 is literal, 1 is inventive.", variable.getDescription());
        assertTrue(variable.hasDescription());
    }

    @Test
    void blankTextCountsAsNoDescription() {
        NodeVariable<Float> variable = new NodeVariable<>("Temperature", Float.class, true).describedAs("   ");

        assertFalse(variable.hasDescription());
    }

    @Test
    void describedAsComposesWithTheOtherMarkers() {
        NodeVariable<String> variable = new NodeVariable<>("API Key", String.class, true)
                .describedAs("Sent as a bearer token.")
                .markSecret()
                .required();

        assertTrue(variable.hasDescription());
        assertTrue(variable.isSecret());
        assertTrue(variable.isRequired());
        // A description is authored on the node type, so it says nothing about what may be saved.
        assertFalse(variable.isPersistentValue());
    }
}
