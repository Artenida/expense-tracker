package com.expensetracker.store;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.Expense;
import com.expensetracker.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcExpenseStoreTest {

    @TempDir
    Path tempDir;

    private Database db;
    private JdbcExpenseStore store;

    /** The same three lines App will run in sprint 13. */
    @BeforeEach
    void setUp() {
        db = new Database(tempDir.resolve("test.db"));
        new SchemaMigrator(db).migrate();
        store = new JdbcExpenseStore(db);
    }

    private static Expense lunch() {
        return Expense.create(new BigDecimal("10.00"), Category.OTHER, "lunch", LocalDate.of(2026, 9, 1));
    }

    // --- round trip ---------------------------------------------------------

    @Test
    void addedExpenseIsFoundById() {
        Expense e = lunch();

        store.add(e);

        assertTrue(store.findById(e.id()).isPresent());
    }

    /** Each field goes through a different conversion; a bug in one is invisible until checked. */
    @Test
    void everyFieldSurvivesTheRoundTrip() {
        Expense original = Expense.create(new BigDecimal("24.90"), Category.GROCERIES,
                "weekly shop", LocalDate.of(2026, 9, 15));
        store.add(original);

        Expense loaded = store.findById(original.id()).orElseThrow();

        assertEquals(original.id(), loaded.id());
        assertEquals(0, original.amount().compareTo(loaded.amount()));
        assertEquals(original.category(), loaded.category());
        assertEquals(original.description(), loaded.description());
        assertEquals(original.date(), loaded.date());
        assertEquals(original.createdAt(), loaded.createdAt());
        assertEquals(original, loaded);
    }

    @Test
    void missingIdReturnsEmpty() {
        assertTrue(store.findById("nope").isEmpty());
    }

    @Test
    void duplicateIdThrows() {
        Expense e = lunch();
        store.add(e);

        StoreException thrown = assertThrows(StoreException.class, () -> store.add(e));

        assertInstanceOf(SQLException.class, thrown.getCause());
        assertTrue(thrown.getMessage().contains(e.id()), thrown::getMessage);
    }

    // --- spec test 5: a new session -----------------------------------------

    /** The second Database does not migrate: the file, not the object, holds the schema and the data. */
    @Test
    void dataWrittenInOneSessionIsPresentInANewSession() {
        Path file = tempDir.resolve("persist.db");

        Database first = new Database(file);
        new SchemaMigrator(first).migrate();
        Expense saved = lunch();
        new JdbcExpenseStore(first).add(saved);

        Database second = new Database(file);
        assertEquals(saved, new JdbcExpenseStore(second).findById(saved.id()).orElseThrow());
    }

    // --- spec test 4: injection ---------------------------------------------

    /** Both halves matter: the value came back unchanged, and the table survived. */
    @Test
    void aDescriptionThatLooksLikeSqlIsStoredAsText() {
        String attack = "'; DROP TABLE expenses; --";

        Expense e = Expense.create(new BigDecimal("1.00"), Category.OTHER, attack, LocalDate.of(2026, 9, 1));
        store.add(e);

        assertEquals(attack, store.findById(e.id()).orElseThrow().description());

        Expense after = Expense.create(new BigDecimal("2.00"), Category.OTHER,
                "still here", LocalDate.of(2026, 9, 2));
        store.add(after);
        assertTrue(store.findById(after.id()).isPresent());
    }

    // --- mapper edge cases --------------------------------------------------

    /** Someone edited the database by hand - hence a plain Statement and a literal. */
    @Test
    void unknownCategoryInTheDatabaseIsReported() throws SQLException {
        insertRaw("('hand-edited', 1000, 'FOOD', 'pizza', '2026-09-01', '2026-09-01T12:00:00Z')");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> store.findById("hand-edited"));

        assertTrue(thrown.getMessage().contains("'FOOD'"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("GROCERIES"), thrown::getMessage);
    }

    @Test
    void amountIsReadAsCentsNotDouble() throws SQLException {
        insertRaw("('raw', 2490, 'OTHER', 'coffee', '2026-09-01', '2026-09-01T12:00:00Z')");

        Expense loaded = store.findById("raw").orElseThrow();

        assertEquals(0, new BigDecimal("24.90").compareTo(loaded.amount()));
    }

    @Test
    void largeAmountsSurvive() {
        Expense big = Expense.create(Money.fromCents(999_999_999), Category.HOUSING,
                "house", LocalDate.of(2026, 9, 1));
        store.add(big);

        assertEquals(999_999_999, Money.toCents(store.findById(big.id()).orElseThrow().amount()));
    }

    /**
     * 999_999_999 still fits in an int, so the test above would pass with getInt. The
     * largest amount Validation allows, 999999999.99, is 99_999_999_999 cents - past
     * Integer.MAX_VALUE - and only survives getLong.
     */
    @Test
    void amountsBeyondIntRangeSurvive() {
        long cents = 99_999_999_999L;
        Expense max = Expense.create(Money.fromCents(cents), Category.HOUSING,
                "castle", LocalDate.of(2026, 9, 1));
        store.add(max);

        assertEquals(cents, Money.toCents(store.findById(max.id()).orElseThrow().amount()));
    }

    private void insertRaw(String values) throws SQLException {
        try (Connection c = db.open();
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO expenses (id, amount_cents, category, description, spent_on, created_at) "
                    + "VALUES " + values);
        }
    }
}
