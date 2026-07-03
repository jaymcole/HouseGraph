package io.github.jaymcole.housegraph;

import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.storage.AppDirectories;
import io.github.jaymcole.housegraph.storage.AppPreferences;
import io.github.jaymcole.housegraph.ui.GraphCanvas;
import io.github.jaymcole.housegraph.ui.GraphFileIO;
import io.github.jaymcole.housegraph.ui.SecretsEditor;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;

/**
 * JavaFX application entry point for HouseGraph.
 */
public class App extends Application {

    private final AppPreferences preferences = AppPreferences.load();

    @Override
    public void start(Stage stage) {
        NodeGraph graph = new NodeGraph();
        GraphCanvas canvas = new GraphCanvas(graph);

        Button saveButton = new Button("Save");
        saveButton.setOnAction(e -> {
            File file = createFileChooser("Save Graph").showSaveDialog(stage);
            if (file == null) {
                return;
            }
            try {
                GraphFileIO.save(canvas, file);
                rememberLastFile(file);
            } catch (IOException ex) {
                new Alert(Alert.AlertType.ERROR, "Failed to save graph: " + ex.getMessage()).showAndWait();
            }
        });

        Button loadButton = new Button("Load");
        loadButton.setOnAction(e -> {
            File file = createFileChooser("Load Graph").showOpenDialog(stage);
            if (file == null) {
                return;
            }
            try {
                GraphFileIO.load(canvas, file);
                rememberLastFile(file);
            } catch (IOException | RuntimeException ex) {
                new Alert(Alert.AlertType.ERROR, "Failed to load graph: " + ex.getMessage()).showAndWait();
            }
        });

        Button secretsButton = new Button("Secrets…");
        secretsButton.setOnAction(e -> SecretsEditor.show(stage));

        ToolBar toolBar = new ToolBar(saveButton, loadButton, secretsButton);

        BorderPane root = new BorderPane();
        root.setTop(toolBar);
        root.setCenter(canvas);

        stage.setTitle("HouseGraph");
        stage.setScene(new Scene(root, 1100, 750));
        stage.show();

        loadLastFileInto(canvas);
    }

    /** Records the just-saved/opened file as the one to reopen on the next launch. */
    private void rememberLastFile(File file) {
        preferences.put(AppPreferences.LAST_FILE, file.getAbsolutePath());
        preferences.save();
    }

    /** Reopens the last file from a previous session, if it's recorded and still there. */
    private void loadLastFileInto(GraphCanvas canvas) {
        preferences.get(AppPreferences.LAST_FILE).ifPresent(path -> {
            File file = new File(path);
            if (!file.isFile()) {
                return;
            }
            try {
                GraphFileIO.load(canvas, file);
            } catch (IOException | RuntimeException ex) {
                System.err.println("Could not reopen last file " + file + ": " + ex);
            }
        });
    }

    private static FileChooser createFileChooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("HouseGraph files", "*.json"));

        File savesDirectory = AppDirectories.get().saves().toFile();
        if (savesDirectory.isDirectory()) {
            chooser.setInitialDirectory(savesDirectory);
        }
        return chooser;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
