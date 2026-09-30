package com.expensetracker.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Knows where the database file is and how to open a correctly configured connection
 * to it. Holds no connection itself: every operation opens one and closes it, which for
 * SQLite on a local file costs microseconds and keeps ownership obvious.
 */
public final class Database {

    private final Path path;

    /**
     * Absolute, because the status bar shows it in sprint 13 and a relative path tells
     * the user nothing about which file is open. Creates the parent directory, which is
     * a no-op when it already exists.
     */
    public Database(Path path) {
        this.path = path.toAbsolutePath();
        try {
            Files.createDirectories(this.path.getParent());
        } catch (IOException e) {
            throw new StoreException("cannot create database directory " + this.path.getParent(), e);
        }
    }

    /**
     * In order: the {@code expenses.db} system property (tests, the packaged build),
     * the macOS Application Support folder, then {@code ./data}. The middle branch
     * checks that {@code ~/Library/Application Support} exists, which it always does on
     * macOS and never on Linux, so no platform check is needed.
     */
    public static Database resolveDefault() {
        String override = System.getProperty("expenses.db");
        if (override != null && !override.isBlank()) {
            return new Database(Path.of(override));
        }

        String home = System.getProperty("user.home");
        Path userData = Path.of(home, "Library", "Application Support", "ExpenseTracker");
        if (Files.isDirectory(userData.getParent())) {
            return new Database(userData.resolve("expenses.db"));
        }

        return new Database(Path.of("data", "expenses.db"));
    }

    /**
     * Returns an <em>open</em> connection that the caller must close - always with
     * try-with-resources. The name is the contract.
     *
     * <p>The pragmas run here, on a fresh connection, because {@code journal_mode}
     * cannot be changed inside a transaction and nothing has begun one yet.
     */
    public Connection open() {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = c.createStatement()) {
                // A reader is not blocked by a writer - matters once sprint 15 overlaps them.
                s.execute("PRAGMA journal_mode = WAL");
                // Wait up to 5 s on a locked file instead of failing with SQLITE_BUSY.
                s.execute("PRAGMA busy_timeout = 5000");
                // Off by default in SQLite; on now so it is already right when a key is added.
                s.execute("PRAGMA foreign_keys = ON");
            } catch (SQLException e) {
                // The connection is not handed out, so it must be closed here.
                c.close();
                throw e;
            }
            return c;
        } catch (SQLException e) {
            throw new StoreException("failed to open database at " + path, e);
        }
    }

    public Path path() {
        return path;
    }
}
