package io.github.jaymcole.housegraph.graph.nodes.viewers;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node.Keywords;
import io.github.jaymcole.housegraph.annotations.Node.Kind;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

@Display.Name("Image Viewer")
@Display.Description("Shows an image on the canvas.")
@Kind(NodeKind.ACTION)
@Keywords({"image", "view", "display", "show", "preview", "picture", "output"})
public class ImageViewerNode extends BaseNode implements NodeContentProvider  {

    private static final double MAX_SIZE = 160;

    private final NodeVariable<Image> imageIn = new NodeVariable<>("image", Image.class).required();
    private ImageView imageViewer;

    @Override
    protected void onExecuted() {
        if (imageViewer != null) {
            imageViewer.setImage(imageIn.getValue());
        }
    }

    @Override
    public void process(ProcessContext ctx) {

    }

    @Override
    public void configureInputs() {
        addInput(imageIn);
    }

    @Override
    public void configureOutputs() {

    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(new FlowPort("", FlowPort.Direction.IN));
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(new FlowPort("", FlowPort.Direction.OUT));
    }

    @Override
    public Node createNodeContent() {
        imageViewer = new ImageView();
        imageViewer.setFitWidth(MAX_SIZE);
        imageViewer.setFitHeight(MAX_SIZE);
        imageViewer.setPreserveRatio(true);
        imageViewer.setSmooth(true);
        return imageViewer;
    }
}
