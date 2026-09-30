package com.expensetracker.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every test gets its own directory from @TempDir, deleted afterwards whether it passed
 * or not. A shared file would make tests order-dependent.
 */
class SchemaMigratorTest {

    @TempDir
    Path tempDir;

    private Database freshDatabase() {
        return new Database(tempDir.resolve("test.db"));
    }

    // --- migration ----------------------------------------------------------

    @Test
    void freshDatabaseStartsAtVersionZero() {
        assertEquals(0, new SchemaMigrator(freshDatabase()).currentVersion());
    }

    @Test
    void migrateCreatesTheFile() {
        Database db = freshDatabase();

        new SchemaMigrator(db).migrate();

        assertTrue(Files.exists(db.path()));
    }

    @Test
    void migrateReachesTargetVersion() {
        SchemaMigrator migrator = new SchemaMigrator(freshDatabase());

        assertEquals(2, migrator.migrate());
        assertEquals(2, migrator.currentVersion());
        assertEquals(SchemaMigrator.TARGET_VERSION, migrator.currentVersion());
    }

    @Test
    void migrateCreatesAllThreeTables() throws SQLException {
        Database db = freshDatabase();
        new SchemaMigrator(db).migrate();

        List<String> tables = names(db, "table");

        assertTrue(tables.containsAll(List.of("expenses", "budgets", "schema_version")),
                () -> "tables were " + tables);
    }

    @Test
    void v2AddsTheNoteColumn() throws SQLException {
        Database db = freshDatabase();
        new SchemaMigrator(db).migrate();

        assertTrue(columnsOf(db, "expenses").contains("note"));
    }

    @Test
    void v2AddsBothIndexes() throws SQLException {
        Database db = freshDatabase();
        new SchemaMigrator(db).migrate();

        List<String> indexes = names(db, "index");

        assertTrue(indexes.containsAll(List.of("idx_expenses_spent_on", "idx_expenses_category")),
                () -> "indexes were " + indexes);
    }

    // --- spec test 11: idempotence ------------------------------------------

    /** The COUNT is the real assertion: "does not throw" would pass for a lucky re-run. */
    @Test
    void runningMigrationsTwiceAppliesNothingTheSecondTime() throws SQLException {
        Database db = freshDatabase();

        new SchemaMigrator(db).migrate();
        new SchemaMigrator(db).migrate();

        try (Connection c = db.open();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM schema_version")) {
            rs.next();
            assertEquals(2, rs.getInt(1), "two rows, not four");
        }
    }

    /** The scenario migrations exist for: an old file meeting a newer app. */
    @Test
    void aDatabaseAtVersionOneIsUpgradedToTwo() throws Exception {
        Database db = freshDatabase();
        runScript(db, "/db/V1__create_tables.sql");
        insertVersionRow(db, 1);
        assertEquals(1, new SchemaMigrator(db).currentVersion());

        assertEquals(2, new SchemaMigrator(db).migrate());
        assertTrue(columnsOf(db, "expenses").contains("note"));
    }

    // --- splitting ----------------------------------------------------------

    @Test
    void statementsAreSplitAndCommentsDropped() {
        String sql = """
                -- a comment
                CREATE TABLE a (x INTEGER);

                CREATE TABLE b (y INTEGER);
                """;

        assertEquals(List.of("CREATE TABLE a (x INTEGER)", "CREATE TABLE b (y INTEGER)"),
                SchemaMigrator.statements(sql));
    }

    // --- helpers ------------------------------------------------------------

    private static List<String> names(Database db, String type) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT name FROM sqlite_master WHERE type = ?")) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString("name"));
                }
            }
        }
        return names;
    }

    private static List<String> columnsOf(Database db, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Connection c = db.open();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        return columns;
    }

    private static void runScript(Database db, String resource) throws IOException, SQLException {
        String sql;
        try (InputStream in = SchemaMigratorTest.class.getResourceAsStream(resource)) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection c = db.open();
             Statement s = c.createStatement()) {
            for (String statement : SchemaMigrator.statements(sql)) {
                s.executeUpdate(statement);
            }
        }
    }

    private static void insertVersionRow(Database db, int version) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO schema_version (version, applied_at) VALUES (?, '2026-01-01T00:00:00Z')")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }
}
