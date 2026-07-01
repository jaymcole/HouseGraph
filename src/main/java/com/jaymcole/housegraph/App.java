package com.jaymcole.housegraph;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * JavaFX application entry point for HouseGraph.
 */
public class App extends Application {

    @Override
    public void start(Stage stage) {
        Label title = new Label("HouseGraph");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");

        Label subtitle = new Label("Home automation graph editor");

        VBox root = new VBox(10, title, subtitle);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(40));

        stage.setTitle("HouseGraph");
        stage.setScene(new Scene(root, 800, 600));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
