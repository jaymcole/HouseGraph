package io.github.jaymcole.housegraph;

import io.github.jaymcole.housegraph.graph.nodes.AddNode;
import io.github.jaymcole.housegraph.graph.nodes.ConstantFloatNode;
import io.github.jaymcole.housegraph.ui.GraphCanvas;
import io.github.jaymcole.housegraph.ui.NodeView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/**
 * JavaFX application entry point for HouseGraph.
 */
public class App extends Application {

    @Override
    public void start(Stage stage) {
        GraphCanvas canvas = new GraphCanvas();

        Button addConstantButton = new Button("Add Constant");
        addConstantButton.setOnAction(e -> canvas.addNode(new NodeView(new ConstantFloatNode(), canvas.getContent(), canvas)));

        Button addSumButton = new Button("Add Sum");
        addSumButton.setOnAction(e -> canvas.addNode(new NodeView(new AddNode(), canvas.getContent(), canvas)));

        ToolBar toolBar = new ToolBar(addConstantButton, addSumButton);

        BorderPane root = new BorderPane();
        root.setTop(toolBar);
        root.setCenter(canvas);

        stage.setTitle("HouseGraph");
        stage.setScene(new Scene(root, 1100, 750));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
