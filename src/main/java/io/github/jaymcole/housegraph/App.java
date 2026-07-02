package io.github.jaymcole.housegraph;

import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.ui.GraphCanvas;
import io.github.jaymcole.housegraph.ui.GraphFileIO;
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
            } catch (IOException | RuntimeException ex) {
                new Alert(Alert.AlertType.ERROR, "Failed to load graph: " + ex.getMessage()).showAndWait();
            }
        });

        ToolBar toolBar = new ToolBar(saveButton, loadButton);

        BorderPane root = new BorderPane();
        root.setTop(toolBar);
        root.setCenter(canvas);

        stage.setTitle("HouseGraph");
        stage.setScene(new Scene(root, 1100, 750));
        stage.show();
    }

    private static FileChooser createFileChooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("HouseGraph files", "*.json"));

        File workingDirectory = new File(System.getProperty("user.dir"));
        if (workingDirectory.isDirectory()) {
            chooser.setInitialDirectory(workingDirectory);
        }
        return chooser;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
