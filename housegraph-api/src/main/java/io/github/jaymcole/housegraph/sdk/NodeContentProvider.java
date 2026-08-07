package io.github.jaymcole.housegraph.sdk;

import io.github.jaymcole.housegraph.graph.BaseNode;
import javafx.scene.Node;

/**
 * Opt-in extension point for a {@link BaseNode} subclass to embed its own JavaFX UI
 * into the box the host draws for it, without needing to know anything about the node
 * view, the canvas, or how nodes are otherwise rendered.
 * <p>
 * If a node's class implements this interface, the host calls
 * {@link #createNodeContent()} once, when the node view is built, and embeds
 * whatever {@link Node} comes back (a Label, a Button, a whole VBox — anything) at
 * the bottom of the node. To keep it updated, override
 * {@link BaseNode#onExecuted()} in the same class and push fresh values into
 * whatever you built.
 * <p>
 * <b>Threading.</b> Both {@code createNodeContent()} and {@link BaseNode#onExecuted()}
 * reach you on the JavaFX Application Thread: the engine runs nodes on background threads
 * but dispatches {@code onExecuted} through the host's callback executor, which the app
 * sets to {@code Platform::runLater} (see {@code NodeGraph#setCallbackExecutor}). So you
 * can touch your controls directly, with no {@code Platform.runLater} of your own. Work
 * you start yourself — a socket bind, a gateway login, an HTTP call — is a different
 * matter: keep that off the FX thread, and hop back with {@code Platform.runLater} to
 * show its result.
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
