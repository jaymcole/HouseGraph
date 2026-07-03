package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.storage.SecretsStore;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Modal editor for the encrypted {@link SecretsStore}: list, add/update, and delete
 * secrets. The value field is masked by default and revealable with a checkbox, and
 * every change is written straight back to the encrypted store.
 */
public final class SecretsEditor {

    private SecretsEditor() {
    }

    public static void show(Window owner) {
        SecretsStore store;
        try {
            store = SecretsStore.open();
        } catch (RuntimeException e) {
            new Alert(Alert.AlertType.ERROR, "Could not open the secrets store: " + e.getMessage()).showAndWait();
            return;
        }

        ListView<String> keyList = new ListView<>();
        keyList.getItems().setAll(store.keys());
        keyList.setPrefWidth(200);

        TextField keyField = new TextField();
        keyField.setPromptText("Key name");

        PasswordField valueMasked = new PasswordField();
        valueMasked.setPromptText("Secret value");
        TextField valueRevealed = new TextField();
        valueRevealed.setPromptText("Secret value");
        // The two fields share one text value; only one is shown at a time.
        valueRevealed.textProperty().bindBidirectional(valueMasked.textProperty());
        setShown(valueRevealed, false);

        CheckBox reveal = new CheckBox("Show value");
        reveal.selectedProperty().addListener((obs, was, show) -> {
            setShown(valueRevealed, show);
            setShown(valueMasked, !show);
        });

        keyList.getSelectionModel().selectedItemProperty().addListener((obs, was, selected) -> {
            if (selected != null) {
                keyField.setText(selected);
                valueMasked.setText(store.get(selected));
            }
        });

        Button newButton = new Button("New");
        newButton.setOnAction(e -> {
            keyList.getSelectionModel().clearSelection();
            keyField.clear();
            valueMasked.clear();
            keyField.requestFocus();
        });

        Button saveButton = new Button("Save");
        saveButton.setOnAction(e -> {
            String key = keyField.getText() == null ? "" : keyField.getText().trim();
            if (key.isEmpty()) {
                new Alert(Alert.AlertType.WARNING, "Enter a key name first.").showAndWait();
                return;
            }
            store.put(key, valueMasked.getText() == null ? "" : valueMasked.getText());
            store.save();
            keyList.getItems().setAll(store.keys());
            keyList.getSelectionModel().select(key);
        });

        Button deleteButton = new Button("Delete");
        deleteButton.setOnAction(e -> {
            String key = keyField.getText() == null ? "" : keyField.getText().trim();
            if (!store.contains(key)) {
                return;
            }
            store.remove(key);
            store.save();
            keyList.getItems().setAll(store.keys());
            keyField.clear();
            valueMasked.clear();
        });

        HBox actions = new HBox(6, newButton, saveButton, deleteButton);

        VBox form = new VBox(6,
                new Label("Key"), keyField,
                new Label("Value"), valueMasked, valueRevealed, reveal,
                actions);
        form.setPadding(new Insets(0, 0, 0, 10));
        VBox.setVgrow(form, Priority.ALWAYS);

        Button closeButton = new Button("Close");
        HBox footer = new HBox(closeButton);
        footer.setPadding(new Insets(10, 0, 0, 0));

        BorderPane root = new BorderPane();
        root.setLeft(keyList);
        root.setCenter(form);
        root.setBottom(footer);
        root.setPadding(new Insets(12));

        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Secrets");
        stage.setScene(new Scene(root, 480, 320));
        closeButton.setOnAction(e -> stage.close());
        stage.showAndWait();
    }

    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setManaged(shown);
        node.setVisible(shown);
    }
}
