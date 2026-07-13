package io.github.jaymcole.housegraph.graph.nodes.control;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;

import java.util.List;

/**
 * Iterates a list, firing its <b>Body</b> flow output once per element and then <b>Completed</b>
 * once at the end. Each iteration exposes the element on the <b>Current Item</b> output and its
 * position on the <b>Index</b> output, so the body subgraph can pull them.
 * <p>
 * <b>Why this isn't just {@code activate(bodyPort)} in a loop.</b> The engine's flow cascade fires
 * each downstream node at most once per run ({@code flowVisited} dedup), so re-activating a port
 * can't run a body more than once. Instead each iteration runs the Body branch as its own isolated
 * sub-run via {@link #runFlowBranchToCompletion}: a fresh run resets the dedup so the body executes
 * afresh for every item, and the sub-run is seeded with this node's per-item outputs (this node is
 * pre-marked complete there, so the body pulls the seeded values without re-running this
 * {@code process()}). See {@code NodeGraph} for the mechanism.
 * <p>
 * <b>Iteration is sequential:</b> item <i>N+1</i>'s body starts only after item <i>N</i>'s body
 * subtree has fully finished, and <b>Completed</b> fires only after the last item. This keeps
 * ordering predictable and makes it safe to touch a shared resource (a Discord send, the Arduino
 * sign) from the body. An empty or null list runs the body zero times and fires <b>Completed</b>
 * straight away; a body iteration that throws is logged by its sub-run and iteration continues.
 * <p>
 * <b>Current Item is typed {@link Object}</b> because a {@code List} variable's element type is
 * erased (the input's type is bare {@code List.class}), so the loop can't know it. Wiring it into a
 * strongly-typed input may therefore need a converter.
 */
@Display.Name("For Each")
public class ForEachNode extends BaseNode {

    @SuppressWarnings("unchecked")
    private final NodeVariable<List<?>> list =
            new NodeVariable<>("List", (Class<List<?>>) (Class<?>) List.class, false).required();
    private final NodeVariable<Object> currentItem = new NodeVariable<>("Current Item", Object.class);
    private final NodeVariable<Integer> index = new NodeVariable<>("Index", Integer.class);

    private final FlowPort bodyPort = new FlowPort("Body", FlowPort.Direction.OUT);
    private final FlowPort completedPort = new FlowPort("Completed", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        List<?> items = list.getValue();
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                int itemIndex = i;
                Object item = items.get(i);
                runFlowBranchToCompletion(bodyPort, () -> {
                    currentItem.setValue(item);
                    index.setValue(itemIndex);
                });
            }
        }
        activate(completedPort);
    }

    @Override
    public void configureInputs() {
        addInput(list);
    }

    @Override
    public void configureOutputs() {
        addOutput(currentItem);
        addOutput(index);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(new FlowPort("", FlowPort.Direction.IN));
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(bodyPort);
        addFlowOutput(completedPort);
    }
}
