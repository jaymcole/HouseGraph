package io.github.jaymcole.housegraph.graph.nodes.debug;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Executable;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.ui.NodeContentProvider;
import javafx.scene.Node;
import javafx.scene.control.Label;

/**
 * Passthrough debug node: shows whatever value is currently on its input. Serves as
 * the minimal example of {@link NodeContentProvider} — a node author only needs to
 * build a small JavaFX snippet in {@link #createNodeContent()} and keep it fresh in
 * {@link #onExecuted()}; nothing else in the codebase needs to change.
 */
@Display.Name("Input Display")
@Executable.ExecutableIn
@Executable.ExecutableOut
public class ValueDisplayNode extends BaseNode implements NodeContentProvider {

    private final NodeVariable<String> value = new NodeVariable<>("Value", String.class);
    private Label valueLabel;

    @Override
    public void process() {
    }

    @Override
    public void configureInputs() {
        addInput(value);
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public Node createNodeContent() {
        valueLabel = new Label(format(value.getValue()));
        valueLabel.setStyle("-fx-text-fill: #dddddd; -fx-font-size: 12px; -fx-alignment: center; -fx-padding: 4;");
        return valueLabel;
    }

    @Override
    protected void onExecuted() {
        if (valueLabel != null) {
            valueLabel.setText(format(value.getValue()));
        }
    }

    private static String format(String f) {
        return f == null ? "—" : String.valueOf(f);
    }
}
