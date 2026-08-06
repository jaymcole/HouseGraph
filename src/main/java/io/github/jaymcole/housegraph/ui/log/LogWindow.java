package io.github.jaymcole.housegraph.ui.log;

import io.github.jaymcole.housegraph.logging.LogBufferSink;
import io.github.jaymcole.housegraph.logging.LogFormat;
import io.github.jaymcole.housegraph.logging.LogLevel;
import io.github.jaymcole.housegraph.logging.LogManager;
import io.github.jaymcole.housegraph.logging.LogRecord;
import io.github.jaymcole.housegraph.logging.LogSink;
import io.github.jaymcole.housegraph.logging.Logging;
import io.github.jaymcole.housegraph.storage.AppPreferences;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToolBar;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The standalone log viewer: a top-level window, independent of the graph window, that
 * renders the shared {@link LogBufferSink}. It is opened from the toolbar and can be closed
 * and reopened freely — because the buffer keeps capturing whether or not this window
 * exists, reopening replays the full retained history via {@link LogBufferSink#snapshot()}
 * and then follows live records through a {@linkplain LogBufferSink#addListener listener}.
 *
 * <p>The window offers three independent controls that mirror the logging model:
 * <ul>
 *   <li>a <b>display filter</b> — hides rows below a chosen level without discarding them
 *       (change it back and they reappear);</li>
 *   <li>a <b>per-output level</b> control for every registered {@link LogSink}, so the user
 *       can, say, make the file quieter while keeping the window verbose; and</li>
 *   <li><b>auto-scroll</b> and <b>clear</b>.</li>
 * </ul>
 *
 * <p>Rows are copy-able: individual cells can be selected, and a right-click menu (or the
 * platform copy shortcut) copies either the focused cell or the selected rows — a row is
 * emitted as tab-separated columns so it stays aligned when pasted.</p>
 *
 * <p>A single instance is reused ({@link #show()} is a toggle-to-front). All mutation of the
 * table happens on the FX thread; the buffer listener marshals each incoming record with
 * {@link Platform#runLater}. The listener is attached on show and removed on hide, so a
 * closed window costs nothing.
 */
public final class LogWindow {

    private static LogWindow instance;

    private final AppPreferences preferences;
    private final Stage stage;
    private final LogBufferSink buffer = Logging.buffer();

    /** All rows currently held by the window (bounded to the buffer's capacity). */
    private final ObservableList<LogRecord> rows = FXCollections.observableArrayList();
    private final FilteredList<LogRecord> visibleRows = new FilteredList<>(rows);

    private final TableView<LogRecord> table = new TableView<>(visibleRows);
    private final CheckBox autoScroll = new CheckBox("Auto-scroll");
    private final ComboBox<LogLevel> displayFilter = new ComboBox<>();

    /** Live listener appending new records; held so it can be detached when the window hides. */
    private final Consumer<LogRecord> liveListener = record -> Platform.runLater(() -> append(record));

    /**
     * Opens the log window, creating it on first use and bringing the existing one to the
     * front thereafter. Must be called on the FX thread.
     *
     * @param preferences the shared store used to remember per-output level choices
     */
    public static void show(AppPreferences preferences) {
        if (instance == null) {
            instance = new LogWindow(preferences);
        }
        instance.open();
    }

    private LogWindow(AppPreferences preferences) {
        this.preferences = preferences;
        stage = new Stage();
        stage.setTitle("HouseGraph Logs");
        stage.setScene(new Scene(buildRoot(), 900, 500));
        // Detach from the buffer whenever the window is hidden (closed) so a shut window is
        // free; re-attached and refreshed on the next open().
        stage.setOnHidden(e -> buffer.removeListener(liveListener));
    }

    private void open() {
        if (stage.isShowing()) {
            stage.toFront();
            return;
        }
        // Replay the full retained history, then follow live. Snapshot-before-listen leaves a
        // vanishingly small window where a record emitted at this exact instant is skipped
        // here; it is still on the console and in the log file.
        rows.setAll(buffer.snapshot());
        buffer.addListener(liveListener);
        scrollToEndIfFollowing();
        stage.show();
        stage.toFront();
    }

    private BorderPane buildRoot() {
        BorderPane root = new BorderPane();
        root.setTop(buildToolBar());
        root.setCenter(buildTable());
        return root;
    }

    private ToolBar buildToolBar() {
        displayFilter.getItems().setAll(LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR);
        displayFilter.getSelectionModel().select(LogLevel.TRACE);
        displayFilter.valueProperty().addListener((obs, was, level) -> applyDisplayFilter(level));
        applyDisplayFilter(LogLevel.TRACE);

        autoScroll.setSelected(true);
        autoScroll.selectedProperty().addListener((obs, was, on) -> {
            if (on) {
                scrollToEndIfFollowing();
            }
        });

        javafx.scene.control.Button clear = new javafx.scene.control.Button("Clear");
        clear.setOnAction(e -> {
            buffer.clear();
            rows.clear();
        });

        ToolBar bar = new ToolBar();
        bar.getItems().addAll(new Label("Show:"), displayFilter, new javafx.scene.control.Separator());
        bar.getItems().addAll(buildOutputLevelControls());
        bar.getItems().addAll(new javafx.scene.control.Separator(), autoScroll, clear);
        return bar;
    }

    /** A per-output level dropdown for every registered sink — this is "per output levels" made visible. */
    private HBox buildOutputLevelControls() {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(new Label("Output levels:"));
        for (LogSink sink : LogManager.get().sinks()) {
            ComboBox<LogLevel> combo = new ComboBox<>();
            combo.getItems().setAll(LogLevel.values());
            combo.getSelectionModel().select(sink.getLevel());
            combo.valueProperty().addListener((obs, was, level) -> {
                sink.setLevel(level);
                LogLevelPreferences.persist(preferences, sink);
            });
            box.getChildren().addAll(new Label(sink.name()), combo);
        }
        return box;
    }

    private TableView<LogRecord> buildTable() {
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.getColumns().setAll(
                column("Time", 105, LogFormat::time),
                column("Level", 60, r -> r.level().name()),
                column("Source", 150, r -> r.source()),
                column("Thread", 130, r -> r.thread()),
                messageColumn());

        // Let the user select an individual cell (not just whole rows) so a single field can be
        // copied on its own; multi-select supports copying a span of rows at once.
        table.getSelectionModel().setCellSelectionEnabled(true);
        table.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);

        // Colour each row by severity so warnings and errors stand out at a glance.
        table.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(LogRecord record, boolean empty) {
                super.updateItem(record, empty);
                setStyle(empty || record == null ? "" : rowStyle(record.level()));
            }
        });

        installCopySupport();
        return table;
    }

    /**
     * Makes the table's contents copy-able: a right-click menu with "Copy Cell" / "Copy Row(s)"
     * and the platform copy shortcut (Ctrl/Cmd+C). A cell copies its own text; a row copies all
     * columns joined by tabs, so pasted rows stay column-aligned in a spreadsheet or editor.
     */
    private void installCopySupport() {
        MenuItem copyCell = new MenuItem("Copy Cell");
        copyCell.setOnAction(e -> copyToClipboard(selectedCellText()));

        MenuItem copyRow = new MenuItem("Copy Row(s)");
        copyRow.setOnAction(e -> copyToClipboard(selectedRowsText()));

        table.setContextMenu(new ContextMenu(copyCell, copyRow));

        // Ctrl+C (Cmd+C on macOS) copies the focused cell, or the selected rows when whole rows
        // are highlighted. TableView has no built-in copy handler, so wire it explicitly.
        KeyCombination copy = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
        table.setOnKeyPressed(e -> {
            if (copy.match(e)) {
                String cell = selectedCellText();
                copyToClipboard(cell.isEmpty() ? selectedRowsText() : cell);
                e.consume();
            }
        });
    }

    /** The text of the single focused/selected cell, or empty if no specific cell is selected. */
    private String selectedCellText() {
        TablePosition<LogRecord, ?> pos = table.getSelectionModel().getSelectedCells().stream()
                .findFirst().orElse(null);
        if (pos == null || pos.getTableColumn() == null || pos.getRow() < 0) {
            return "";
        }
        LogRecord record = table.getItems().get(pos.getRow());
        Object value = pos.getTableColumn().getCellObservableValue(record).getValue();
        return value == null ? "" : value.toString();
    }

    /** Every selected row rendered as tab-separated columns, rows joined by newlines. */
    private String selectedRowsText() {
        List<Integer> rowIndices = table.getSelectionModel().getSelectedCells().stream()
                .map(TablePosition::getRow)
                .filter(i -> i >= 0)
                .distinct()
                .sorted()
                .toList();
        return rowIndices.stream()
                .map(i -> rowText(table.getItems().get(i)))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    /** One record's columns joined by tabs, in on-screen column order. */
    private String rowText(LogRecord record) {
        return table.getColumns().stream()
                .map(col -> {
                    Object value = col.getCellObservableValue(record).getValue();
                    return value == null ? "" : value.toString();
                })
                .collect(Collectors.joining("\t"));
    }

    private static void copyToClipboard(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private TableColumn<LogRecord, String> messageColumn() {
        // The message column carries the throwable summary too, and takes the remaining width.
        TableColumn<LogRecord, String> column = column("Message", 400, r ->
                r.throwable() == null ? r.message() : r.message() + "  ⟶ " + r.throwable());
        column.setPrefWidth(450);
        return column;
    }

    private TableColumn<LogRecord, String> column(String title, double width, java.util.function.Function<LogRecord, String> value) {
        TableColumn<LogRecord, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        column.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
            }
        });
        return column;
    }

    private void append(LogRecord record) {
        rows.add(record);
        // Keep the window's own list bounded to the buffer capacity so it can't outgrow it.
        int overflow = rows.size() - buffer.capacity();
        if (overflow > 0) {
            rows.remove(0, overflow);
        }
        scrollToEndIfFollowing();
    }

    private void applyDisplayFilter(LogLevel minimum) {
        visibleRows.setPredicate(record -> record.level().isAtLeast(minimum));
        scrollToEndIfFollowing();
    }

    private void scrollToEndIfFollowing() {
        if (autoScroll.isSelected() && !visibleRows.isEmpty()) {
            table.scrollTo(visibleRows.size() - 1);
        }
    }

    private static String rowStyle(LogLevel level) {
        return switch (level) {
            case ERROR -> "-fx-background-color: #f8d7da;";
            case WARN -> "-fx-background-color: #fff3cd;";
            case DEBUG, TRACE -> "-fx-text-fill: #6c757d;";
            default -> "";
        };
    }
}
