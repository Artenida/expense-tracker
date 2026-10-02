package com.expensetracker.ui;

import com.expensetracker.domain.ExpenseFilter;
import com.expensetracker.service.ImportService;
import com.expensetracker.ui.task.BackgroundRunner;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** The file choosers and the import progress dialog, in front of sprint 12's ImportService. */
public final class ImportExportActions {

    private final ImportService imports;
    private final BackgroundRunner runner;
    private final Supplier<Window> owner;
    private final Runnable onChange;

    /**
     * {@code owner} is a supplier because the window does not exist yet when the view is
     * built. {@code onChange} is MainView::reload.
     */
    public ImportExportActions(ImportService imports, BackgroundRunner runner,
                               Supplier<Window> owner, Runnable onChange) {
        this.imports = Objects.requireNonNull(imports, "imports");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    /**
     * Writes what the user is looking at, not the whole database. No progress dialog: a
     * month exports instantly, and a dialog that flashes and vanishes is worse than none.
     */
    public void exportCsv(ExpenseFilter filter) {
        FileChooser chooser = csvChooser("Export expenses");
        chooser.setInitialFileName("expenses-" + filter.month() + ".csv");

        // showSaveDialog asks "already exists, replace?" itself, in the platform's style.
        Optional<Path> chosen = Optional.ofNullable(chooser.showSaveDialog(owner.get())).map(File::toPath);
        chosen.ifPresent(path -> runner.run(() -> {
            imports.exportCsv(path, filter);
            return null;
        }, ignored -> { }, ErrorDialogs::show));
    }

    public void importCsv() {
        // null on Cancel - a JavaFX API that predates Optional, so wrap it at once.
        Optional<Path> chosen = Optional.ofNullable(csvChooser("Import expenses").showOpenDialog(owner.get()))
                .map(File::toPath);
        chosen.ifPresent(this::importFrom);
    }

    /**
     * The one hand-written Task in the project: it reports progress and can be cancelled,
     * which a Supplier cannot express.
     *
     * <p>Cancel is this class's own flag, not task.cancel(). task.cancel() moves the task
     * to CANCELLED immediately - onCancelled fires while call() is still inside the
     * insert loop, before the rollback. Polling a flag instead means call() returns
     * normally once addAll has rolled back, so the dialog closes when the database is
     * actually back where it was.
     */
    private void importFrom(Path path) {
        AtomicBoolean cancelRequested = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();

        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() {
                // Progress stays at -1, the animated indeterminate bar, until the total is known.
                updateMessage("Reading " + path.getFileName() + "…");
                long total = expectedRows(path);     // once - not once per row
                AtomicBoolean started = new AtomicBoolean();
                return imports.importCsv(path, done -> {
                    if (started.compareAndSet(false, true)) {
                        updateMessage("Importing " + (total > 0 ? total + " " : "") + "expenses…");
                    }
                    if (total > 0) {
                        updateProgress(done, total);
                    }
                }, cancelRequested::get);
            }
        };

        Dialog<Void> progress = buildProgressDialog(task, cancelRequested, finished);

        task.setOnSucceeded(e -> {
            close(progress, finished);
            int count = task.getValue();
            // A cancel that arrived after the last check, during the commit, still imported
            // everything - report what happened, not what was asked for.
            if (cancelRequested.get() && count == 0) {
                inform("Import cancelled. Nothing was imported.");
            } else {
                onChange.run();
                inform(count + (count == 1 ? " expense" : " expenses") + " imported.");
            }
        });
        task.setOnFailed(e -> {
            close(progress, finished);
            ErrorDialogs.show(task.getException());
        });

        runner.submit(task);
        // show(), not showAndWait(): the dialog is closed by the task's handlers, which run
        // on this same thread. Blocking here for the dialog would wait for itself.
        progress.show();
    }

    private Dialog<Void> buildProgressDialog(Task<Integer> task, AtomicBoolean cancelRequested,
                                             AtomicBoolean finished) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner.get());
        // Blocks the main window: the import is changing the data it displays.
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Importing");

        // Bound, not set: the bar follows the task with no update code, on the right thread.
        ProgressBar bar = new ProgressBar();
        bar.setPrefWidth(320);
        bar.progressProperty().bind(task.progressProperty());

        Label status = new Label();
        status.textProperty().bind(task.messageProperty());

        VBox content = new VBox(10, status, bar);
        content.setPadding(new Insets(16));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        Button cancel = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        Runnable requestCancel = () -> {
            cancelRequested.set(true);
            cancel.setDisable(true);
            dialog.setHeaderText("Cancelling - rolling back…");
        };
        // Consumed, so the button does not close the dialog: it stays up until the import
        // has actually stopped and rolled back.
        cancel.addEventFilter(ActionEvent.ACTION, event -> {
            requestCancel.run();
            event.consume();
        });
        // The window's close control is a cancel too - otherwise the dialog could be
        // dismissed while the import carried on invisibly. Once finished, close normally.
        dialog.setOnCloseRequest(event -> {
            if (!finished.get()) {
                requestCancel.run();
                event.consume();
            }
        });
        return dialog;
    }

    /** Marks the import finished first, so the close request handler lets this close through. */
    private static void close(Dialog<Void> dialog, AtomicBoolean finished) {
        finished.set(true);
        dialog.close();
    }

    private void inform(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        alert.initOwner(owner.get());
        alert.setTitle("Expense Tracker");
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private static FileChooser csvChooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        return chooser;
    }

    /**
     * The progress bar's denominator: records, not lines, so the header is not counted.
     * An extra pass over the file, cheap at any size that fits in memory. -1 when the file
     * cannot be read, which leaves the bar indeterminate rather than crashing; the import
     * itself then reports the real problem.
     */
    static long expectedRows(Path path) {
        try (Stream<String> lines = Files.lines(path)) {
            return Math.max(1, lines.count() - 1);
        } catch (IOException | UncheckedIOException e) {
            return -1;
        }
    }
}
