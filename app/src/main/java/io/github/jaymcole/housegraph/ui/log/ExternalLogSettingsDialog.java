package io.github.jaymcole.housegraph.ui.log;

import io.github.jaymcole.housegraph.logging.DiscordWebhookSink;
import io.github.jaymcole.housegraph.logging.LogLevel;
import io.github.jaymcole.housegraph.storage.AppPreferences;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * The log window's <b>External…</b> dialog: where a log destination that sends off this
 * machine is configured. One destination exists so far — a Discord webhook — with its own
 * level, so a headless machine can post warnings and errors to a channel while the file and
 * the window stay verbose.
 *
 * <p>Modal and owned by the log window, unlike the window itself: this edits a setting and
 * is done, so there is nothing to watch alongside the canvas. {@link ExternalLogDestinations}
 * does the saving and the registering; this only collects the three values and reports what
 * came back.
 *
 * <p><b>Test messages run off the FX thread.</b> Posting to a webhook is a network round
 * trip, and the one thing a user wants from this dialog is to find out whether the URL they
 * pasted works — so the button posts on a worker and marshals the answer back with
 * {@link Platform#runLater}, leaving the dialog responsive while it waits.
 */
final class ExternalLogSettingsDialog {

    private static final DateTimeFormatter TEST_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private ExternalLogSettingsDialog() {
    }

    /**
     * Shows the dialog and blocks until it is dismissed.
     *
     * @param owner       the log window, so the dialog centres on it
     * @param preferences the shared store the settings are saved to
     * @param onSaved     run after a successful save, so the log window can rebuild its
     *                    per-output level controls for a destination that just appeared
     */
    static void show(Window owner, AppPreferences preferences, Runnable onSaved) {
        ExternalLogDestinations.DiscordSettings saved = ExternalLogDestinations.discordSettings(preferences);

        CheckBox enabled = new CheckBox("Send log records to a Discord webhook");
        enabled.setSelected(saved.enabled());

        PasswordField urlMasked = new PasswordField();
        urlMasked.setPromptText("https://discord.com/api/webhooks/…");
        urlMasked.setText(saved.webhookUrl());
        TextField urlRevealed = new TextField();
        urlRevealed.setPromptText("https://discord.com/api/webhooks/…");
        // One value behind two fields, as in SecretsEditor: only one is shown at a time.
        urlRevealed.textProperty().bindBidirectional(urlMasked.textProperty());
        setShown(urlRevealed, false);

        CheckBox reveal = new CheckBox("Show URL");
        reveal.selectedProperty().addListener((obs, was, show) -> {
            setShown(urlRevealed, show);
            setShown(urlMasked, !show);
        });

        ComboBox<LogLevel> level = new ComboBox<>();
        // No OFF: the checkbox above is what switches the destination off, and offering two
        // ways to do the same thing only invites a destination that is on but silent.
        level.getItems().setAll(LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR);
        level.getSelectionModel().select(saved.level());

        Label status = new Label();
        status.setWrapText(true);

        Button test = new Button("Send test message");
        Button save = new Button("Save");
        Button cancel = new Button("Cancel");

        Stage stage = new Stage();
        stage.setTitle("External logging");
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);

        test.setOnAction(e -> {
            String url = urlMasked.getText() == null ? "" : urlMasked.getText().trim();
            if (!DiscordWebhookSink.isWebhookUrl(url)) {
                status.setText("That is not a Discord webhook URL. Copy it from the channel's"
                        + " Integrations ▸ Webhooks page — it starts https://discord.com/api/webhooks/.");
                return;
            }
            test.setDisable(true);
            status.setText("Sending…");
            sendTest(url, level.getValue(), result -> {
                test.setDisable(false);
                status.setText(result);
            });
        });

        save.setOnAction(e -> {
            String url = urlMasked.getText() == null ? "" : urlMasked.getText().trim();
            if (enabled.isSelected() && !DiscordWebhookSink.isWebhookUrl(url)) {
                status.setText("Enter the channel's webhook URL before switching the destination on,"
                        + " or clear the checkbox to leave it off.");
                return;
            }
            ExternalLogDestinations.applyDiscord(preferences,
                    new ExternalLogDestinations.DiscordSettings(enabled.isSelected(), url, level.getValue()));
            onSaved.run();
            stage.close();
        });

        cancel.setOnAction(e -> stage.close());

        HBox actions = new HBox(6, test, new Separator(Orientation.VERTICAL), save, cancel);
        actions.setAlignment(Pos.CENTER_LEFT);

        Label heading = new Label("Discord");
        heading.setStyle("-fx-font-weight: bold;");

        Label explain = new Label("The webhook URL is a credential — anyone holding it can post to the"
                + " channel — so it is kept in the encrypted secret store, not in preferences.");
        explain.setWrapText(true);
        explain.setStyle("-fx-text-fill: #6c757d;");

        VBox form = new VBox(8,
                heading,
                explain,
                enabled,
                new Label("Webhook URL"), urlMasked, urlRevealed, reveal,
                new Label("Send at this level and above"), level,
                new Separator(),
                actions,
                status);
        form.setPadding(new Insets(14));
        VBox.setVgrow(form, Priority.ALWAYS);

        BorderPane root = new BorderPane(form);
        stage.setScene(new Scene(root, 520, 400));
        stage.showAndWait();
    }

    /**
     * Posts a one-off message on a worker thread and hands the outcome back on the FX thread.
     * Deliberately builds a throwaway sink rather than reusing the registered one: the point
     * is to test the URL currently in the field, which may be neither saved nor enabled.
     */
    private static void sendTest(String url, LogLevel level, Consumer<String> onResult) {
        Thread worker = new Thread(() -> {
            String result;
            DiscordWebhookSink probe = null;
            try {
                probe = new DiscordWebhookSink(url, level);
                probe.sendNow("HouseGraph test message — external logging is configured, and will"
                        + " forward records at " + level + " and above.");
                result = "Sent. Check the channel.";
            } catch (IOException | RuntimeException ex) {
                result = "Could not send: " + ex.getMessage();
            } finally {
                if (probe != null) {
                    probe.close();
                }
            }
            String message = LocalTime.now().format(TEST_TIME) + " — " + result;
            Platform.runLater(() -> onResult.accept(message));
        }, "housegraph-discord-log-test");
        worker.setDaemon(true);
        worker.start();
    }

    /** Shows or hides a field without leaving a gap where it was. */
    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
