package com.expensetracker.store;

import java.sql.Connection;
import java.sql.SQLException;

/** Small transaction helpers shared by the migrator and the stores. */
final class Jdbc {

    private Jdbc() {
    }

    /**
     * A failed rollback must not replace the exception that caused it, so it is
     * attached to the original as suppressed - still in the stack trace, not in charge.
     */
    static void rollbackQuietly(Connection c, Exception cause) {
        try {
            c.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }
}
