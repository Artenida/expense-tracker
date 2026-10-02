package com.expensetracker.store;

import org.sqlite.SQLiteException;

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

    /**
     * What kind of failure, decided here from SQLite's result code - where the driver
     * types are legal - so the UI can pick a sentence without matching on driver
     * messages or importing driver classes.
     */
    public enum Reason { READ_ONLY, LOCKED, OTHER }

    private final Reason reason;

    public StoreException(String message, Throwable cause) {
        super(message, cause);
        this.reason = classify(cause);
    }

    public StoreException(String message) {
        super(message);
        this.reason = Reason.OTHER;
    }

    public Reason reason() {
        return reason;
    }

    /**
     * Walks the cause chain for a SQLiteException and reads its primary result code - the
     * low byte, so extended codes such as SQLITE_READONLY_DBMOVED count as READ_ONLY.
     */
    private static Reason classify(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof SQLiteException e && e.getResultCode() != null) {
                return switch (e.getResultCode().code & 0xFF) {
                    case 8 -> Reason.READ_ONLY;         // SQLITE_READONLY
                    case 5, 6 -> Reason.LOCKED;         // SQLITE_BUSY, SQLITE_LOCKED
                    default -> Reason.OTHER;
                };
            }
        }
        return Reason.OTHER;
    }
}
