package io.github.jaymcole.housegraph.ui.view;

import io.github.jaymcole.housegraph.graph.BaseNode;
import javafx.scene.Node;

/**
 * Opt-in extension point for a {@link BaseNode} subclass to embed its own JavaFX UI
 * into its {@link NodeView}, without needing to know anything about NodeView,
 * GraphCanvas, or how nodes are otherwise rendered.
 * <p>
 * If a node's class implements this interface, {@link NodeView} calls
 * {@link #createNodeContent()} once, when the node view is built, and embeds
 * whatever {@link Node} comes back (a Label, a Button, a whole VBox — anything) at
 * the bottom of the node. To keep it updated, override
 * {@link BaseNode#onExecuted()} in the same class and push fresh values into
 * whatever you built.
 * <p>
 * Example — a node that just displays its input value:
 * <pre>{@code
 * public class ValueDisplayNode extends BaseNode implements NodeContentProvider {
 *     private final NodeVariable<Float> value = new NodeVariable<>("Value", Float.class);
 *     private Label label;
 *
 *     public Node createNodeContent() {
 *         label = new Label("—");
 *         return label;
 *     }
 *
 *     protected void onExecuted() {
 *         label.setText(String.valueOf(value.getValue()));
 *     }
 *
 *     // process()/configureInputs()/configureOutputs() as usual
 * }
 * }</pre>
 */
public interface NodeContentProvider {

    Node createNodeContent();
}
