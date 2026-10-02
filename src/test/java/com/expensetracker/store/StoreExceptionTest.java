package com.expensetracker.store;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.Expense;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The reason is read from SQLite's result code here, so the UI never parses driver messages. */
class StoreExceptionTest {

    @TempDir
    Path tempDir;

    @Test
    void readOnlyCodesAreReadOnly() {
        assertEquals(StoreException.Reason.READ_ONLY,
                reasonFor(SQLiteErrorCode.SQLITE_READONLY));
        // Extended codes share the primary code's low byte.
        assertEquals(StoreException.Reason.READ_ONLY,
                reasonFor(SQLiteErrorCode.SQLITE_READONLY_DBMOVED));
    }

    @Test
    void busyAndLockedAreLocked() {
        assertEquals(StoreException.Reason.LOCKED, reasonFor(SQLiteErrorCode.SQLITE_BUSY));
        assertEquals(StoreException.Reason.LOCKED, reasonFor(SQLiteErrorCode.SQLITE_LOCKED));
        assertEquals(StoreException.Reason.LOCKED, reasonFor(SQLiteErrorCode.SQLITE_BUSY_TIMEOUT));
    }

    @Test
    void anythingElseIsOther() {
        assertEquals(StoreException.Reason.OTHER, reasonFor(SQLiteErrorCode.SQLITE_CONSTRAINT));
        assertEquals(StoreException.Reason.OTHER, new StoreException("no cause").reason());
        assertEquals(StoreException.Reason.OTHER,
                new StoreException("plain", new SQLException("not sqlite")).reason());
    }

    @Test
    void theCauseChainIsSearched() {
        SQLException wrapper = new SQLException("outer",
                new SQLiteException("inner", SQLiteErrorCode.SQLITE_READONLY));

        assertEquals(StoreException.Reason.READ_ONLY, new StoreException("wrapped", wrapper).reason());
    }

    /** The real thing, not a constructed code: a write to a file the process cannot write. */
    @Test
    void writingToAReadOnlyFileIsReportedAsReadOnly() throws IOException {
        Path file = tempDir.resolve("readonly.db");
        Database db = new Database(file);
        new SchemaMigrator(db).migrate();
        JdbcExpenseStore store = new JdbcExpenseStore(db);

        // Close every handle to the -wal/-shm side files by not holding a connection, then lock it.
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        assumeTrue(!Files.isWritable(file), "running as a user that ignores permissions");
        try {
            StoreException e = assertThrows(StoreException.class, () -> store.add(
                    Expense.create(BigDecimal.TEN, Category.OTHER, "lunch", LocalDate.of(2026, 9, 1))));

            assertEquals(StoreException.Reason.READ_ONLY, e.reason(), () -> "cause was: " + e.getCause());
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    private static StoreException.Reason reasonFor(SQLiteErrorCode code) {
        return new StoreException("test", new SQLiteException("test", code)).reason();
    }
}
