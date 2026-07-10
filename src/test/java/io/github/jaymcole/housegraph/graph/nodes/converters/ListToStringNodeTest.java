package io.github.jaymcole.housegraph.graph.nodes.converters;

import io.github.jaymcole.housegraph.graph.NodeVariable;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ListToStringNodeTest {

    @SuppressWarnings("unchecked")
    private static void setInput(ListToStringNode node, List<?> value) {
        ((NodeVariable<Object>) node.getInputs().get(0)).setValue(value);
    }

    private static String output(ListToStringNode node) {
        return (String) node.getOutputs().get(0).getValue();
    }

    @Test
    void joinsEntriesWithNewlines() {
        ListToStringNode node = new ListToStringNode();
        setInput(node, List.of("fox squirrel (87%)", "acorn (4%)", "wood (2%)"));

        node.process();

        assertEquals("fox squirrel (87%)\nacorn (4%)\nwood (2%)", output(node));
    }

    @Test
    void singleEntryHasNoTrailingNewline() {
        ListToStringNode node = new ListToStringNode();
        setInput(node, List.of("only"));

        node.process();

        assertEquals("only", output(node));
    }

    @Test
    void emptyOrNullListYieldsEmptyString() {
        ListToStringNode empty = new ListToStringNode();
        setInput(empty, List.of());
        empty.process();
        assertEquals("", output(empty));

        ListToStringNode nul = new ListToStringNode();
        setInput(nul, null);
        nul.process();
        assertEquals("", output(nul));
    }

    @Test
    void stringifiesNonStringAndNullEntries() {
        ListToStringNode node = new ListToStringNode();
        setInput(node, Arrays.asList(1, null, true));

        node.process();

        assertEquals("1\nnull\ntrue", output(node));
    }
}
