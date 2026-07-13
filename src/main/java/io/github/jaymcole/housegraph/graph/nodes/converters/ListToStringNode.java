package io.github.jaymcole.housegraph.graph.nodes.converters;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;

import java.util.List;
import java.util.StringJoiner;

/**
 * Flattens a list into a single string, one entry per line (entries joined with
 * {@code \n}). Each element is stringified with {@link String#valueOf}, so the input
 * needn't be a {@code List<String>} — anything a node emits as a {@code List} works
 * (e.g. the Animal Classifier's {@code Objects} output) — and null entries render as
 * {@code "null"}. A null or empty list yields an empty string.
 */
@Display.Name("List to String")
public class ListToStringNode extends BaseNode {

    @SuppressWarnings("unchecked")
    private final NodeVariable<List<?>> in =
            new NodeVariable<>("in", (Class<List<?>>) (Class<?>) List.class, false).required();
    private final NodeVariable<String> out = new NodeVariable<>("out", String.class, false);

    @Override
    public void process(ProcessContext ctx) {
        List<?> list = in.getValue();
        if (list == null || list.isEmpty()) {
            out.setValue("");
            return;
        }
        StringJoiner joiner = new StringJoiner("\n");
        for (Object entry : list) {
            joiner.add(String.valueOf(entry));
        }
        out.setValue(joiner.toString());
    }

    @Override
    public void configureInputs() {
        addInput(in);
    }

    @Override
    public void configureOutputs() {
        addOutput(out);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(new FlowPort("", FlowPort.Direction.IN));
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(new FlowPort("", FlowPort.Direction.OUT));
    }
}
