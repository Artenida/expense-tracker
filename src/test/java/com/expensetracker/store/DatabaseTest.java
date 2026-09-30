package com.expensetracker.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class DatabaseTest {

    @TempDir
    Path tempDir;

    /**
     * A system property set by one test and not cleared leaks into every test that runs
     * after it in the same JVM. Cleared after every test, not only the one that sets it,
     * so a failing assertion cannot skip the cleanup.
     */
    @AfterEach
    void clearOverride() {
        System.clearProperty("expenses.db");
    }

    @Test
    void openReturnsAUsableConnection() throws SQLException {
        Database db = new Database(tempDir.resolve("test.db"));

        try (Connection c = db.open();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void parentDirectoryIsCreated() {
        Path nested = tempDir.resolve("a/b/c/test.db");

        new Database(nested);

        assertTrue(Files.isDirectory(tempDir.resolve("a/b/c")));
    }

    /** The status bar depends on this. */
    @Test
    void pathIsAbsolute() {
        Database db = new Database(Path.of("relative", "test.db"));
        try {
            assertTrue(db.path().isAbsolute(), () -> "was " + db.path());
        } finally {
            // The constructor created ./relative in the project root; tidy it away.
            db.path().getParent().toFile().delete();
        }
    }

    @Test
    void walModeIsEnabled() throws SQLException {
        Database db = new Database(tempDir.resolve("test.db"));

        try (Connection c = db.open();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA journal_mode")) {
            rs.next();
            assertEquals("wal", rs.getString(1));
        }
    }

    @Test
    void systemPropertyOverridesTheDefault() {
        Path override = tempDir.resolve("override.db");
        System.setProperty("expenses.db", override.toString());

        assertEquals(override.toAbsolutePath(), Database.resolveDefault().path());
    }

    // --- error handling -----------------------------------------------------

    @Test
    void openingAnUnwritableLocationThrowsStoreException() throws IOException {
        Database db = new Database(readOnlyDirectory().resolve("test.db"));

        assertThrows(StoreException.class, db::open);
    }

    /** The one that pays off at three in the morning: the cause carries SQLite's error code. */
    @Test
    void theCauseIsPreserved() throws IOException {
        Database db = new Database(readOnlyDirectory().resolve("test.db"));

        StoreException thrown = assertThrows(StoreException.class, db::open);

        assertNotNull(thrown.getCause());
        assertInstanceOf(SQLException.class, thrown.getCause());
    }

    /**
     * Skipped when running as root, which ignores permissions - the test would then
     * fail for a reason that has nothing to do with the code.
     */
    private Path readOnlyDirectory() throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve("read-only"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        assumeFalse(Files.isWritable(dir), "running with permissions that ignore read-only");
        return dir;
    }
}
