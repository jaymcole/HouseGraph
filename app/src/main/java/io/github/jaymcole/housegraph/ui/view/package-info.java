/**
 * The visual layer: JavaFX views that render the model on the canvas.
 * <p>
 * {@link io.github.jaymcole.housegraph.ui.view.NodeView} renders a {@code BaseNode};
 * {@link io.github.jaymcole.housegraph.ui.view.PortView} /
 * {@link io.github.jaymcole.housegraph.ui.view.FlowPortView} render its data / flow anchors
 * (both are {@link io.github.jaymcole.housegraph.ui.view.EdgeAnchor}s); and
 * {@link io.github.jaymcole.housegraph.ui.view.EdgeView} /
 * {@link io.github.jaymcole.housegraph.ui.view.FlowEdgeView} render the connecting curves
 * (both extend {@link io.github.jaymcole.housegraph.ui.view.AbstractEdgeView}, a
 * {@link io.github.jaymcole.housegraph.ui.view.ConnectionView}).
 * {@link io.github.jaymcole.housegraph.ui.view.NodeContentProvider} is the extension point a
 * node implements to embed its own inline UI; {@link io.github.jaymcole.housegraph.ui.view.ExecutionPolicyIcons}
 * draws the per-node policy glyphs. All view code runs on the JavaFX Application Thread.
 * See {@code docs/architecture/ui.md}.
 */
package io.github.jaymcole.housegraph.ui.view;
