package com.expensetracker.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Brings a database file up to the current schema, one numbered, one-way step at a
 * time, recording each step in {@code schema_version}. Runs before the window is
 * shown: a half-migrated database reaching an interactive UI is worse than an app that
 * refuses to start.
 */
public final class SchemaMigrator {

    /**
     * Hardcoded, in order. A classpath directory cannot be reliably listed once it is
     * inside a jar, and the order migrations run in is too important to leave to a
     * directory listing anyway.
     */
    private static final List<String> MIGRATIONS = List.of(
            "V1__create_tables.sql",
            "V2__add_indexes.sql"
    );

    public static final int TARGET_VERSION = MIGRATIONS.size();

    private final Database database;

    public SchemaMigrator(Database database) {
        this.database = database;
    }

    /** Applies every migration above the current version. Returns the version now applied. */
    public int migrate() {
        int current = currentVersion();
        for (int version = current + 1; version <= MIGRATIONS.size(); version++) {
            apply(version, MIGRATIONS.get(version - 1));
        }
        return MIGRATIONS.size();
    }

    /** 0 for a brand new file, which has no {@code schema_version} table yet. */
    public int currentVersion() {
        try (Connection c = database.open();
             Statement s = c.createStatement()) {
            // sqlite_master is SQLite's own catalogue - asking it is how to check for a
            // table without relying on a query failing.
            try (ResultSet rs = s.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'schema_version'")) {
                if (!rs.next()) {
                    return 0;
                }
            }
            try (ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new StoreException("failed to read schema version from " + database.path(), e);
        }
    }

    /**
     * The statements and the version row share one transaction, so either the schema
     * changed <em>and</em> says so, or neither happened. Recorded separately, a crash in
     * between would leave a database claiming a version it does not have.
     *
     * <p>The rollback sits in an inner try so it runs while the connection is still
     * open; the outer try-with-resources then closes it. Closing ends the connection,
     * so there is no autocommit to restore.
     */
    private void apply(int version, String name) {
        List<String> statements = statements(read(name));

        try (Connection c = database.open()) {
            try {
                c.setAutoCommit(false);
                try (Statement s = c.createStatement()) {
                    for (String sql : statements) {
                        s.executeUpdate(sql);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)")) {
                    ps.setInt(1, version);
                    ps.setString(2, Instant.now().toString());
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                rollbackQuietly(c, e);
                throw new StoreException("migration " + name + " failed", e);
            }
        } catch (SQLException e) {
            // Only close() can land here; the body's failures were wrapped above.
            throw new StoreException("failed to close connection after migration " + name, e);
        }
    }

    /**
     * A failed rollback must not replace the exception that caused it, so it is
     * attached to the original as suppressed - still in the stack trace, not in charge.
     */
    private static void rollbackQuietly(Connection c, SQLException cause) {
        try {
            c.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private static String read(String name) {
        // Leading slash: from the root of the classpath, so it works in the IDE, under
        // Maven, and inside the packaged jar alike - a File path would not.
        try (InputStream in = SchemaMigrator.class.getResourceAsStream("/db/" + name)) {
            if (in == null) {
                throw new StoreException("migration not found on classpath: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StoreException("failed to read migration " + name, e);
        }
    }

    /**
     * SQLite compiles one statement per call and silently ignores the rest, so the file
     * must be split. Splitting on ';' is naive: migration files must not contain a
     * semicolon inside a string literal or a comment. Comment lines are stripped first
     * so a statement preceded by one is not mistaken for part of the comment.
     */
    static List<String> statements(String sql) {
        String withoutComments = sql.lines()
                .filter(line -> !line.strip().startsWith("--"))
                .collect(Collectors.joining("\n"));
        return Arrays.stream(withoutComments.split(";"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
