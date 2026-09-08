package io.github.jaymcole.housegraph.search;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Find-in-graph, headlessly: the canvas only paints what these answers say, so everything worth
 * asserting about the feature is assertable here.
 */
class GraphSearchTest {

    @Test
    void matchesTheDisplayNameWhateverTheCase() {
        assertTrue(GraphSearch.compile("thermostat").matches(new ThermostatNode()));
        assertTrue(GraphSearch.compile("THERMO").matches(new ThermostatNode()));
        assertFalse(GraphSearch.compile("humidistat").matches(new ThermostatNode()));
    }

    @Test
    void matchesAClassNameBothRunTogetherAndSplitOnCamelCase() {
        BaseNode node = new ThermostatNode();
        // A user has no way to know which of the two readings the node's own text is stored in, so
        // both have to work or the feature is a coin toss.
        assertTrue(GraphSearch.compile("thermostatnode").matches(node), "the raw identifier");
        assertTrue(GraphSearch.compile("thermostat node").matches(node), "the same identifier, spaced");
    }

    @Test
    void matchesAPortName() {
        assertTrue(GraphSearch.compile("target temperature").matches(new ThermostatNode()));
        assertTrue(GraphSearch.compile("Reached").matches(new ThermostatNode()), "a named flow port");
    }

    @Test
    void matchesTextTheUserTypedIntoTheNode() {
        ThermostatNode node = new ThermostatNode();
        node.setRoom("Upstairs Landing");

        // The point of the feature: every Thermostat in a graph has the same name, and the room is
        // the only thing telling them apart.
        assertTrue(GraphSearch.compile("landing").matches(node));
        assertFalse(GraphSearch.compile("landing").matches(new ThermostatNode()));
    }

    @Test
    void punctuationInAValueDoesNotStopItBeingFound() {
        ThermostatNode node = new ThermostatNode();
        node.setRoom("192.168.0.14");

        assertTrue(GraphSearch.compile("192.168.0.14").matches(node));
        assertTrue(GraphSearch.compile("168 0 14").matches(node), "query and value normalise alike");
    }

    @Test
    void neverMatchesASecret() {
        ThermostatNode node = new ThermostatNode();
        node.setApiKey("hunter2");

        // The same gate persistence uses: a secret no more reaches a highlight than it reaches a
        // save file. A find that confirmed one a character at a time would hand back exactly the
        // value the store exists to keep out of everything the app draws.
        assertFalse(GraphSearch.compile("hunter2").matches(node));
    }

    @Test
    void neverMatchesAComputedValue() {
        ThermostatNode node = new ThermostatNode();
        node.setLastReading("21.5 degrees");

        // Otherwise what a find highlights would depend on whether the graph had run yet.
        assertFalse(GraphSearch.compile("degrees").matches(node));
    }

    @Test
    void aBlankQueryMatchesNothing() {
        assertTrue(GraphSearch.compile("").isBlank());
        assertTrue(GraphSearch.compile("   ").isBlank());
        assertTrue(GraphSearch.compile(null).isBlank());
        assertFalse(GraphSearch.compile("  ").matches(new ThermostatNode()));
        assertEquals(List.of(), GraphSearch.matches(List.of(new ThermostatNode()), ""));
    }

    @Test
    void aMatchCannotRunAcrossTwoUnrelatedFields() {
        // "Thermostat" is the end of the name and "Target Temperature" the start of the next field;
        // a haystack that simply concatenated them would report a hit here.
        assertFalse(GraphSearch.compile("thermostat target").matches(new ThermostatNode()));
    }

    @Test
    void filteringACollectionKeepsTheOrderItWasGiven() {
        BaseNode first = new ThermostatNode();
        BaseNode other = new SirenNode();
        BaseNode last = new ThermostatNode();

        assertEquals(List.of(first, last),
                GraphSearch.matches(List.of(first, other, last), "thermostat"));
    }

    @Display.Name("Thermostat")
    static class ThermostatNode extends BaseNode {

        private final NodeVariable<String> room = new NodeVariable<>("Room", String.class, true);
        private final NodeVariable<Float> target = new NodeVariable<>("Target Temperature", Float.class, true);
        private final NodeVariable<String> apiKey =
                new NodeVariable<String>("API Key", String.class, true).markSecret();
        private final NodeVariable<String> lastReading =
                new NodeVariable<String>("Last Reading", String.class, true).transientValue();

        void setRoom(String value) {
            room.setValue(value);
        }

        void setApiKey(String value) {
            apiKey.setValue(value);
        }

        void setLastReading(String value) {
            lastReading.setValue(value);
        }

        @Override
        public void configureInputs() {
            addInput(room);
            addInput(target);
            addInput(apiKey);
        }

        @Override
        public void configureOutputs() {
            addOutput(lastReading);
        }

        @Override
        public void configureFlowInputs() {
            addFlowInput(new FlowPort("", FlowPort.Direction.IN));
        }

        @Override
        public void configureFlowOutputs() {
            addFlowOutput(new FlowPort("Reached", FlowPort.Direction.OUT));
        }

        @Override
        public void process(ProcessContext ctx) {
        }
    }

    @Display.Name("Siren")
    static class SirenNode extends BaseNode {

        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
        }

        @Override
        public void process(ProcessContext ctx) {
        }
    }
}
