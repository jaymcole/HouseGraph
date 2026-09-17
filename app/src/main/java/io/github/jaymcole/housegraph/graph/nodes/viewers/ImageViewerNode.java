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
import javafx.scene.layout.StackPane;

@Display.Name("Image Viewer")
@Display.Description("Shows an image on the canvas.")
@Kind(NodeKind.ACTION)
@Keywords({"image", "view", "display", "show", "preview", "picture", "output"})
public class ImageViewerNode extends BaseNode implements NodeContentProvider  {

    /**
     * The preview's size on a node nobody has resized. A floor rather than a cap now: the frame
     * below takes whatever space the node gives it, so dragging the node bigger is how you get a
     * bigger preview.
     */
    private static final double DEFAULT_SIZE = 160;

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
        imageViewer.setPreserveRatio(true);
        imageViewer.setSmooth(true);

        // The viewer fills the box the node gives it instead of a fixed square, so resizing the node
        // is how you get a bigger preview - on the one node where that is most of the reason to.
        // An ImageView is not resizable and cannot be told to fill anything, so it goes in a frame
        // that is, with its fit box following that frame's size. The frame's preferred size is set
        // outright, which is both what keeps an unresized node looking exactly as it did and what
        // keeps the binding from feeding back into it: the frame's size comes from the layout above,
        // never from the image inside it.
        StackPane frame = new StackPane(imageViewer);
        frame.setMinSize(DEFAULT_SIZE, DEFAULT_SIZE);
        frame.setPrefSize(DEFAULT_SIZE, DEFAULT_SIZE);
        imageViewer.fitWidthProperty().bind(frame.widthProperty());
        imageViewer.fitHeightProperty().bind(frame.heightProperty());
        return frame;
    }
}
