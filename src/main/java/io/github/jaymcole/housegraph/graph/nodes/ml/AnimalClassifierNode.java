package io.github.jaymcole.housegraph.graph.nodes.ml;

import ai.djl.modality.Classifications;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.ml.AnimalVerdict;
import io.github.jaymcole.housegraph.ml.ImageNetClassifier;
import io.github.jaymcole.housegraph.ui.NodeContentProvider;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;

import java.awt.image.BufferedImage;

/**
 * Answers one question about a frame: <b>is there a squirrel or a bird?</b> It runs the
 * locally-loaded {@link ImageNetClassifier} (ResNet-50 / ImageNet, via DJL — no Python)
 * on the input {@link Image} and collapses the model's 1000 fine-grained labels into a
 * coarse verdict — {@code squirrel}, {@code bird}, {@code other}, or {@code none} — since
 * species don't matter here. This is the classifier-first milestone toward feature parity
 * with the Python sibling project; localization/detection comes later.
 * <p>
 * <b>How the verdict is chosen.</b> The model's top predictions are scanned in
 * descending probability and the first one that maps to a squirrel- or bird-type label
 * (and clears {@code Threshold}) wins — so the animal still counts when the model's #1
 * guess is some background object. If nothing squirrel/bird is found, the verdict is
 * {@code other} when the model was at least confident about <em>something</em> (its top
 * guess cleared the threshold) and {@code none} otherwise (empty scene / too unsure).
 * <p>
 * <b>Outputs</b> are made to wire straight into the existing control-flow nodes:
 * {@code Category} (String) for display/logging, {@code Confidence} (Float), and the two
 * convenience gates {@code Is Squirrel} / {@code Is Bird} — each {@code 1}/{@code 0} so
 * they drop directly into an {@code If} node (which fires on any non-zero condition) to
 * trigger, say, the squirrel alarm. Wire a repeating trigger (or a motion event) into the
 * flow input so it re-evaluates each fresh frame. Best fed a reasonably tight crop of the
 * subject (e.g. a camera snapshot of a feeder); whole wide scenes dilute the classifier.
 * <p>
 * The heavy model is loaded lazily and shared across all classifier nodes, so the first
 * evaluation after launch pays a one-time download/load cost and later ones are fast.
 */
@Display.Name("Animal Classifier")
public class AnimalClassifierNode extends BaseNode implements NodeContentProvider {

    /** How many of the model's ranked predictions to consider when looking for an animal. */
    private static final int TOP_K = 5;

    private static final float DEFAULT_THRESHOLD = 0.1f;

    private final NodeVariable<Image> imageIn = new NodeVariable<>("Image", Image.class).required();
    private final NodeVariable<Float> threshold = new NodeVariable<>("Threshold", Float.class, true);

    private final NodeVariable<String> category = new NodeVariable<>("Category", String.class);
    private final NodeVariable<Float> confidence = new NodeVariable<>("Confidence", Float.class);
    private final NodeVariable<Float> isSquirrel = new NodeVariable<>("Is Squirrel", Float.class);
    private final NodeVariable<Float> isBird = new NodeVariable<>("Is Bird", Float.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    /** Last raw top-1 ImageNet label, kept only for the inline UI readout. */
    private volatile String lastLabel;
    private Label display;

    public AnimalClassifierNode() {
        threshold.setValue(DEFAULT_THRESHOLD);
    }

    @Override
    public void process() {
        Image fxImage = imageIn.getValue();
        if (fxImage == null) {
            throw new IllegalStateException("No image on the Image input");
        }
        BufferedImage awtImage = SwingFXUtils.fromFXImage(fxImage, null);
        if (awtImage == null) {
            throw new IllegalStateException("Could not read the input image");
        }

        Classifications result = ImageNetClassifier.getInstance().classify(awtImage);
        AnimalVerdict.Result verdict = AnimalVerdict.decide(result, minConfidence(), TOP_K);

        category.setValue(verdict.category());
        confidence.setValue((float) verdict.confidence());
        isSquirrel.setValue(AnimalVerdict.SQUIRREL.equals(verdict.category()) ? 1f : 0f);
        isBird.setValue(AnimalVerdict.BIRD.equals(verdict.category()) ? 1f : 0f);
        lastLabel = verdict.label();
    }

    private float minConfidence() {
        Float value = threshold.getValue();
        if (value == null) {
            return DEFAULT_THRESHOLD;
        }
        return Math.max(0f, Math.min(1f, value));
    }

    @Override
    public void configureInputs() {
        addInput(imageIn);
        addInput(threshold);
    }

    @Override
    public void configureOutputs() {
        addOutput(category);
        addOutput(confidence);
        addOutput(isSquirrel);
        addOutput(isBird);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }

    @Override
    public Node createNodeContent() {
        display = new Label(readout());
        display.setStyle("-fx-text-fill: #dddddd; -fx-font-size: 12px; -fx-alignment: center; -fx-padding: 4;");
        return display;
    }

    @Override
    protected void onExecuted() {
        if (display != null) {
            display.setText(readout());
        }
    }

    private String readout() {
        String verdict = category.getValue();
        if (verdict == null) {
            return "—";
        }
        Float conf = confidence.getValue();
        String pct = conf == null ? "" : String.format("  %.0f%%", conf * 100f);
        String detail = lastLabel == null ? "" : "\n" + lastLabel;
        return verdict + pct + detail;
    }
}
