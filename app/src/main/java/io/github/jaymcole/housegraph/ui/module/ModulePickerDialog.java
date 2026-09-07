package io.github.jaymcole.housegraph.ui.module;

import io.github.jaymcole.housegraph.modules.ModuleChoices;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/**
 * Asks which module a new node should reference.
 *
 * <p>A deliberately thin shell, the way {@code ui.plugin.PluginWindow} is: it renders the rows
 * {@link ModuleChoices} produced and returns the one that was picked. What is offered, in what
 * order, how it is summarised and why the graph being edited is not in the list are all decided in
 * {@code modules/}, where they are unit-tested without a display.
 */
public final class ModulePickerDialog {

    private ModulePickerDialog() {
    }

    /**
     * Shows the picker and blocks until it is answered.
     *
     * <p>Must be called on the FX Application Thread, with {@code choices} already loaded — the scan
     * behind them belongs on a worker.
     *
     * @param owner   the window to centre on, or null
     * @param choices what to offer, in display order; an empty list shows the nothing-to-pick notice
     * @return the chosen module, or empty when the dialog was cancelled or had nothing to offer
     */
    public static Optional<ModuleChoices.Choice> show(Window owner, List<ModuleChoices.Choice> choices) {
        Dialog<ModuleChoices.Choice> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Add Module");
        dialog.setHeaderText(choices.isEmpty()
                ? "No modules are published on this machine."
                : "Which module should this node run?");
        dialog.getDialogPane().setMinWidth(520);

        if (choices.isEmpty()) {
            dialog.getDialogPane().setContent(new Label(
                    "Open a graph that has Module Input, Output, Entry or Exit nodes in it and choose"
                            + " File ▸ Publish as Module… to make it referenceable."));
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
            dialog.showAndWait();
            return Optional.empty();
        }

        ListView<ModuleChoices.Choice> list = new ListView<>();
        list.getItems().setAll(choices);
        list.setCellFactory(view -> new ChoiceCell());
        list.getSelectionModel().selectFirst();
        list.setPrefHeight(260);

        dialog.getDialogPane().setContent(list);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button ->
                button == ButtonType.OK ? list.getSelectionModel().getSelectedItem() : null);
        return dialog.showAndWait();
    }

    /** Name, then the interface on one line, then whatever would stop it working. */
    private static final class ChoiceCell extends ListCell<ModuleChoices.Choice> {

        @Override
        protected void updateItem(ModuleChoices.Choice choice, boolean empty) {
            super.updateItem(choice, empty);
            if (empty || choice == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Label name = new Label(choice.name());
            name.setStyle("-fx-font-weight: bold;");
            VBox rows = new VBox(2, name, dim(choice.summary()));
            if (!choice.needs().isEmpty()) {
                rows.getChildren().add(dim("Needs " + String.join(", ", choice.needs())));
            }
            for (String problem : choice.problems()) {
                Label warning = new Label(problem);
                warning.setStyle("-fx-text-fill: #ff6b6b;");
                warning.setWrapText(true);
                rows.getChildren().add(warning);
            }
            setText(null);
            setGraphic(rows);
        }

        private static Label dim(String text) {
            Label label = new Label(text);
            label.setStyle("-fx-opacity: 0.7;");
            return label;
        }
    }
}
