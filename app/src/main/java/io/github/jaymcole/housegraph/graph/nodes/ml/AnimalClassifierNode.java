package io.github.jaymcole.housegraph.graph.nodes.ml;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import ai.djl.modality.Classifications;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.ml.AnimalVerdict;
import io.github.jaymcole.housegraph.ml.ImageNetClassifier;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

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
 * {@code Category} (String) for display/logging, {@code Confidence} (Float), the two
 * convenience gates {@code Is Squirrel} / {@code Is Bird} — each {@code 1}/{@code 0} so
 * they drop directly into an {@code If} node (which fires on any non-zero condition) to
 * trigger, say, the squirrel alarm — and {@code Objects} (List of String), the model's
 * top-K raw labels with confidences (e.g. {@code ["fox squirrel (87%)", "wood rabbit (4%)"]})
 * for display/logging or downstream iteration. Wire a repeating trigger (or a motion event) into the
 * flow input so it re-evaluates each fresh frame. Best fed a reasonably tight crop of the
 * subject (e.g. a camera snapshot of a feeder); whole wide scenes dilute the classifier.
 * <p>
 * The heavy model is loaded lazily and shared across all classifier nodes, so the first
 * evaluation after launch pays a one-time download/load cost and later ones are fast.
 */
@Display.Name("Animal Classifier")
public class AnimalClassifierNode extends BaseNode {

    /** How many of the model's ranked predictions to consider when looking for an animal. */
    private static final int TOP_K = 5;

    private static final float DEFAULT_THRESHOLD = 0.1f;

    private final NodeVariable<Image> imageIn = new NodeVariable<>("Image", Image.class).required();
    private final NodeVariable<Float> threshold = new NodeVariable<>("Threshold", Float.class, true);

    private final NodeVariable<String> category = new NodeVariable<>("Category", String.class);
    private final NodeVariable<Float> confidence = new NodeVariable<>("Confidence", Float.class);
    private final NodeVariable<Float> isSquirrel = new NodeVariable<>("Is Squirrel", Float.class);
    private final NodeVariable<Float> isBird = new NodeVariable<>("Is Bird", Float.class);
    @SuppressWarnings("unchecked")
    private final NodeVariable<List<String>> objects =
            new NodeVariable<>("Objects", (Class<List<String>>) (Class<?>) List.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    public AnimalClassifierNode() {
        threshold.setValue(DEFAULT_THRESHOLD);
    }

    @Override
    public void process(ProcessContext ctx) {
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
        objects.setValue(formatObjects(result));
    }

    /**
     * The model's top-K predictions as a list of readable labels, one per entry, e.g.
     * {@code ["fox squirrel (87%)", "acorn (4%)"]}.
     */
    private static List<String> formatObjects(Classifications result) {
        List<String> labels = new ArrayList<>();
        for (Classifications.Classification c : result.topK(TOP_K)) {
            labels.add(String.format("%s (%.0f%%)", c.getClassName(), c.getProbability() * 100));
        }
        return labels;
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
        addOutput(objects);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }
}
