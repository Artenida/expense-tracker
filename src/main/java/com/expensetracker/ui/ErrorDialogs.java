package com.expensetracker.ui;

import com.expensetracker.domain.ValidationException;
import com.expensetracker.io.CsvFormatException;
import com.expensetracker.service.ExpenseNotFoundException;
import com.expensetracker.store.StoreException;
import javafx.application.Platform;
import javafx.scene.control.Alert;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * U-3: two audiences, two outputs. The user gets a sentence they can act on; the log
 * gets the full trace. A dialog full of stack trace, or a friendly message with the cause
 * thrown away, is each half of the mistake.
 */
public final class ErrorDialogs {

    private static final Logger LOG = Logger.getLogger(ErrorDialogs.class.getName());

    private ErrorDialogs() {
    }

    public static void show(Throwable error) {
        // showAndWait off the FX thread throws. Every caller is a setOnFailed handler,
        // which is already on it; this makes the constraint explicit rather than assumed.
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> show(error));
            return;
        }
        LOG.log(Level.SEVERE, "error surfaced to the user", error);

        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Expense Tracker");
        alert.setHeaderText(headerFor(error));
        alert.setContentText(messageFor(error));
        alert.showAndWait();
    }

    /**
     * The default is required: the compiler cannot prove every Throwable is covered, and
     * without it an unexpected type would throw MatchException - an error report turned
     * into a second error.
     */
    static String headerFor(Throwable error) {
        return switch (error) {
            case ValidationException ignored -> "That does not look right";
            case ExpenseNotFoundException ignored -> "Already gone";
            case CsvFormatException ignored -> "The file could not be read";
            case StoreException ignored -> "Could not reach the database";
            default -> "Something went wrong";
        };
    }

    static String messageFor(Throwable error) {
        return switch (error) {
            case ValidationException e -> String.join("\n", e.errors());
            // Every caller reloads before showing this, which is what makes the last sentence true.
            case ExpenseNotFoundException ignored -> "That expense no longer exists. It may have "
                    + "been deleted in another window. The list has been refreshed.";
            case CsvFormatException e -> e.getMessage() + "\n\nNothing was imported.";
            case StoreException e -> databaseHint(e);
            default -> "See the log for details.";
        };
    }

    /** From the store's own classification of SQLite's result code, not from the driver's wording. */
    private static String databaseHint(StoreException error) {
        return switch (error.reason()) {
            case READ_ONLY -> "The database file is read-only. Check the file permissions.";
            case LOCKED -> "The database is in use by another program. Close it and try again.";
            case OTHER -> "The database could not be opened or written to. See the log for details.";
        };
    }
}
