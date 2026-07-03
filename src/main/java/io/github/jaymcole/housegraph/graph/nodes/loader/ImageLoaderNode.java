package io.github.jaymcole.housegraph.graph.nodes.loader;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.ui.NodeContentProvider;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;

/**
 * Source node with no inputs: its button opens a file chooser, and whatever image you
 * pick becomes its output. The image is loaded once, at pick-time (like a constant),
 * not reloaded from disk on every process() call.
 */
@Display.Name("Image Loader")
public class ImageLoaderNode extends BaseNode implements NodeContentProvider {

    private static final double PREVIEW_SIZE = 80;

    private final NodeVariable<Image> imageOut = new NodeVariable<>("image", Image.class);

    private ImageView preview;
    private Label statusLabel;

    @Override
    public void process() {
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
        addOutput(imageOut);
    }

    @Override
    public Node createNodeContent() {
        preview = new ImageView();
        preview.setFitWidth(PREVIEW_SIZE);
        preview.setFitHeight(PREVIEW_SIZE);
        preview.setPreserveRatio(true);

        statusLabel = new Label("No image selected");
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(160);
        statusLabel.setAlignment(Pos.CENTER);

        Button chooseButton = new Button("Choose Image...");
        chooseButton.setMaxWidth(Double.MAX_VALUE);
        chooseButton.setOnAction(event -> chooseImage(chooseButton));

        VBox box = new VBox(6, chooseButton, preview, statusLabel);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private void chooseImage(Node dialogOwner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose Image");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Image files", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));

        File file = chooser.showOpenDialog(dialogOwner.getScene().getWindow());
        if (file == null) {
            return;
        }

        Image image = new Image(file.toURI().toString());
        if (image.isError()) {
            imageOut.setValue(null);
            preview.setImage(null);
            statusLabel.setText("Failed to load: " + file.getName());
            return;
        }

        imageOut.setValue(image);
        preview.setImage(image);
        statusLabel.setText(file.getName());
    }
}
