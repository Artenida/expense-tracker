package com.expensetracker.ui;

import com.expensetracker.domain.ValidationException;
import com.expensetracker.io.CsvFormatException;
import com.expensetracker.service.ExpenseNotFoundException;
import com.expensetracker.store.StoreException;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The message table, without a toolkit: only show() builds an Alert. */
class ErrorDialogsTest {

    @Test
    void eachExceptionTypeGetsItsOwnMessage() {
        assertTrue(ErrorDialogs.messageFor(new ExpenseNotFoundException("x")).contains("no longer exists"));
        assertTrue(ErrorDialogs.messageFor(new ValidationException(List.of("amount is required")))
                .contains("amount is required"));
        assertTrue(ErrorDialogs.messageFor(new CsvFormatException(5, "bad amount")).contains("line 5"));
    }

    @Test
    void eachExceptionTypeGetsItsOwnHeader() {
        assertEquals("Already gone", ErrorDialogs.headerFor(new ExpenseNotFoundException("x")));
        assertEquals("That does not look right", ErrorDialogs.headerFor(new ValidationException(List.of("a"))));
        assertEquals("The file could not be read", ErrorDialogs.headerFor(new CsvFormatException(2, "b")));
        assertEquals("Could not reach the database", ErrorDialogs.headerFor(new StoreException("c")));
        assertEquals("Something went wrong", ErrorDialogs.headerFor(new IllegalStateException("d")));
    }

    @Test
    void everyValidationErrorIsListed() {
        String message = ErrorDialogs.messageFor(
                new ValidationException(List.of("amount is required", "description is required")));

        assertEquals("amount is required\ndescription is required", message);
    }

    @Test
    void theDatabaseHintFollowsTheStoreReason() {
        assertTrue(ErrorDialogs.messageFor(storeFailure(SQLiteErrorCode.SQLITE_READONLY)).contains("read-only"));
        assertTrue(ErrorDialogs.messageFor(storeFailure(SQLiteErrorCode.SQLITE_BUSY)).contains("in use"));
        assertTrue(ErrorDialogs.messageFor(new StoreException("boom")).contains("See the log"));
    }

    @Test
    void anUnexpectedExceptionStillProducesAMessage() {
        assertNotNull(ErrorDialogs.messageFor(new IllegalStateException("surprise")));
        assertNotNull(ErrorDialogs.headerFor(new IllegalStateException("surprise")));
    }

    /** U-3 as an assertion. */
    @Test
    void noMessageContainsAStackTrace() {
        Stream.of(new ExpenseNotFoundException("x"),
                        new StoreException("boom", new RuntimeException()),
                        storeFailure(SQLiteErrorCode.SQLITE_READONLY),
                        new IllegalStateException("surprise"))
                .forEach(e -> assertFalse(ErrorDialogs.messageFor(e).contains("\tat "),
                        "message must not contain a stack trace"));
    }

    /** The developer's message names an internal id; the user's sentence should not. */
    @Test
    void storeMessagesAreNotShownToTheUser() {
        String message = ErrorDialogs.messageFor(new StoreException("failed to delete expense abc-123"));

        assertFalse(message.contains("abc-123"));
    }

    private static StoreException storeFailure(SQLiteErrorCode code) {
        return new StoreException("failed", new SQLiteException("driver text", code));
    }
}
