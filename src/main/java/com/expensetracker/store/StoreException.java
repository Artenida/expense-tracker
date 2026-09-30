package com.expensetracker.store;

/**
 * What the store throws instead of {@code SQLException}. Unchecked, so the database
 * cannot leak into every layer's signatures through {@code throws} clauses.
 *
 * <p>The message is for a developer reading a log - "failed to load expense abc-123".
 * The user sees something friendlier, written in sprint 18. Always pass the cause: it
 * carries SQLite's error code, the only clue to whether a file was locked, missing or
 * unreadable.
 */
public class StoreException extends RuntimeException {

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public StoreException(String message) {
        super(message);
    }
}
