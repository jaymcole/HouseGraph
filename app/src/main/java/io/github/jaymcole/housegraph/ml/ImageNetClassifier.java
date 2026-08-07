package io.github.jaymcole.housegraph.ml;

import ai.djl.Application;
import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.TranslateException;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * JVM-native image classifier: a ResNet-50 trained on ImageNet-1k, run locally via
 * Deep Java Library's PyTorch engine. Given a frame it returns the top ImageNet
 * classes and their probabilities; it has no idea what a "squirrel" or a "bird" is —
 * mapping those 1000 raw labels into whatever categories a caller cares about is the
 * caller's job (see {@code AnimalClassifierNode}). Keeping this class label-agnostic
 * is what lets other nodes reuse the same loaded model for different questions.
 * <p>
 * This is the first of the planned local-model wrappers, and it deliberately mirrors
 * the pattern the Python sibling project uses for every model: <b>lazy, cached, and
 * shared</b>. The heavyweight {@link ZooModel} — whose first load downloads the
 * PyTorch native library and the model weights into DJL's on-disk cache — is loaded
 * once, on first {@link #classify}, and reused process-wide through the {@link #INSTANCE}
 * singleton so multiple classifier nodes don't each pay that cost. A fresh
 * {@link Predictor} is created per call because a {@code Predictor} is not
 * thread-safe, while the model and {@code newPredictor()} are; this lets the graph
 * engine run several classifications concurrently on its virtual threads.
 * <p>
 * Intentionally free of JavaFX: it works on {@link BufferedImage} so it stays in the
 * headless, engine-side half of the app. UI-side nodes convert their JavaFX
 * {@code Image} to a {@code BufferedImage} (via {@code SwingFXUtils}) before calling in.
 */
public final class ImageNetClassifier {

    /** Shared instance so every classifier node reuses one loaded model. */
    private static final ImageNetClassifier INSTANCE = new ImageNetClassifier();

    /** The loaded model, or null until the first {@link #classify} triggers the load. */
    private volatile ZooModel<Image, Classifications> model;

    private ImageNetClassifier() {
    }

    public static ImageNetClassifier getInstance() {
        return INSTANCE;
    }

    /**
     * Classify one image, returning every ImageNet class scored by probability
     * (descending). Loads the model on first use.
     *
     * @param awtImage the frame to classify, as a java.awt image
     * @return the model's full {@link Classifications} result
     * @throws IllegalStateException if the model can't be loaded or inference fails
     */
    public Classifications classify(BufferedImage awtImage) {
        ZooModel<Image, Classifications> loaded = model();
        Image image = ImageFactory.getInstance().fromImage(awtImage);
        try (Predictor<Image, Classifications> predictor = loaded.newPredictor()) {
            return predictor.predict(image);
        } catch (TranslateException e) {
            throw new IllegalStateException("Image classification failed", e);
        }
    }

    private ZooModel<Image, Classifications> model() {
        ZooModel<Image, Classifications> loaded = model;
        if (loaded == null) {
            synchronized (this) {
                loaded = model;
                if (loaded == null) {
                    loaded = load();
                    model = loaded;
                }
            }
        }
        return loaded;
    }

    private ZooModel<Image, Classifications> load() {
        // Resolves to the PyTorch zoo's traced_resnet50 (ImageNet). Only the "layers"
        // filter is used: the PyTorch resnet artifact doesn't carry the MXNet zoo's
        // "flavor"/"dataset" properties, and adding them yields "No matching filter found".
        Criteria<Image, Classifications> criteria = Criteria.builder()
                .optApplication(Application.CV.IMAGE_CLASSIFICATION)
                .setTypes(Image.class, Classifications.class)
                .optArtifactId("resnet")
                .optFilter("layers", "50")
                .optEngine("PyTorch")
                .optProgress(new ProgressBar())
                .build();
        try {
            System.out.println("Loading ImageNet ResNet-50 classifier "
                    + "(first use downloads the PyTorch runtime and model weights)…");
            ZooModel<Image, Classifications> loaded = criteria.loadModel();
            System.out.println("ImageNet classifier loaded");
            return loaded;
        } catch (IOException | ModelException e) {
            throw new IllegalStateException("Could not load the ImageNet classifier model", e);
        }
    }
}
