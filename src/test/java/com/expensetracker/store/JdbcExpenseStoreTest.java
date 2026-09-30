package com.expensetracker.store;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.Expense;
import com.expensetracker.domain.ExpenseFilter;
import com.expensetracker.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static final BigDecimal TEN = new BigDecimal("10.00");

    private static Expense lunch() {
        return Expense.create(new BigDecimal("10.00"), Category.OTHER, "lunch", LocalDate.of(2025, 9, 1));
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

    // --- sprint 08: the fixture -------------------------------------------

    private static Expense expense(String amount, Category category, String description, String date) {
        return Expense.create(new BigDecimal(amount), category, description, LocalDate.parse(date));
    }

    /**
     * The two adjacent-month rows are the point: a subtly wrong BETWEEN passes without
     * them. A past year, because Expense rejects future dates - with the fixture in
     * September 2026, the October row was "tomorrow" when this was written on 2026-09-30,
     * and every seeded test failed on a validation error instead of testing the query.
     */
    private void seed() {
        store.add(expense("24.90", Category.GROCERIES, "shop", "2025-09-15"));
        store.add(expense("12.00", Category.TRANSPORT, "bus", "2025-09-15"));      // same day
        store.add(expense("40.00", Category.GROCERIES, "market", "2025-09-01"));   // first of month
        store.add(expense("15.00", Category.LEISURE, "cinema", "2025-09-30"));     // last of month
        store.add(expense("99.00", Category.GROCERIES, "august", "2025-08-31"));   // adjacent month
        store.add(expense("11.00", Category.HOUSING, "october", "2025-10-01"));    // adjacent month
    }

    private static final ExpenseFilter SEPTEMBER = ExpenseFilter.of(YearMonth.of(2025, 9));

    private List<String> descriptions(ExpenseFilter filter) {
        return store.find(filter).stream().map(Expense::description).toList();
    }

    // --- filtering ----------------------------------------------------------

    @Test
    void findReturnsOnlyTheRequestedMonth() {
        seed();

        List<String> found = descriptions(SEPTEMBER);

        assertEquals(4, found.size(), () -> "found " + found);
        assertFalse(found.contains("august"));
        assertFalse(found.contains("october"));
    }

    @Test
    void theFirstDayOfTheMonthIsIncluded() {
        seed();

        assertTrue(descriptions(SEPTEMBER).contains("market"));
    }

    @Test
    void theLastDayOfTheMonthIsIncluded() {
        seed();

        assertTrue(descriptions(SEPTEMBER).contains("cinema"));
    }

    @Test
    void findWithACategoryFiltersToIt() {
        seed();

        List<Expense> found = store.find(ExpenseFilter.of(YearMonth.of(2025, 9), Category.GROCERIES));

        assertEquals(2, found.size());
        assertTrue(found.stream().allMatch(e -> e.category() == Category.GROCERIES));
    }

    @Test
    void findWithoutACategoryReturnsAll() {
        seed();

        assertEquals(4, store.find(SEPTEMBER).size());
    }

    @Test
    void findReturnsEmptyForAMonthWithNothing() {
        seed();

        assertTrue(store.find(ExpenseFilter.of(YearMonth.of(2020, 1))).isEmpty());
    }

    @Test
    void theReturnedListIsImmutable() {
        seed();
        List<Expense> result = store.find(SEPTEMBER);

        assertThrows(UnsupportedOperationException.class, () -> result.add(null));
    }

    // --- ordering -----------------------------------------------------------

    @Test
    void resultsAreNewestFirst() {
        seed();
        List<Expense> found = store.find(SEPTEMBER);

        assertEquals(LocalDate.of(2025, 9, 30), found.get(0).date());
        assertEquals(LocalDate.of(2025, 9, 1), found.get(3).date());
    }

    /** Twenty runs because an unstable order is usually stable - it documents the requirement. */
    @Test
    void sameDayOrderingIsStableAcrossRuns() {
        seed();

        List<String> first = store.find(SEPTEMBER).stream().map(Expense::id).toList();
        for (int i = 0; i < 20; i++) {
            assertEquals(first, store.find(SEPTEMBER).stream().map(Expense::id).toList());
        }
    }

    /**
     * Pins the tiebreak itself: same day, so the later created_at comes first.
     *
     * <p>Inserted in the <em>opposite</em> order to created_at, on purpose. Without the
     * tiebreakers SQLite walks the spent_on index backwards and returns same-day rows
     * newest-inserted first - so a test that inserted them in created_at order passed
     * even with the ORDER BY reduced to spent_on alone. The ids run against created_at
     * too, so id DESC cannot produce the right answer by itself either.
     */
    @Test
    void sameDayExpensesAreOrderedByCreatedAtDescending() {
        Instant earlier = Instant.parse("2025-09-15T09:00:00Z");
        Instant later = Instant.parse("2025-09-15T18:00:00Z");
        store.add(Expense.restore("a-evening", new BigDecimal("1.00"), Category.OTHER,
                "evening", LocalDate.of(2025, 9, 15), later));
        store.add(Expense.restore("z-morning", new BigDecimal("1.00"), Category.OTHER,
                "morning", LocalDate.of(2025, 9, 15), earlier));

        assertEquals(List.of("evening", "morning"), descriptions(SEPTEMBER));
    }

    // --- update -------------------------------------------------------------

    @Test
    void updateChangesTheStoredValues() {
        Expense original = lunch();
        store.add(original);
        LocalDate newDate = LocalDate.of(2025, 9, 2);

        store.update(original.edit(new BigDecimal("12.50"), Category.HEALTH, "pharmacy", newDate));

        Expense loaded = store.findById(original.id()).orElseThrow();
        assertEquals(0, new BigDecimal("12.50").compareTo(loaded.amount()));
        assertEquals(Category.HEALTH, loaded.category());
        assertEquals("pharmacy", loaded.description());
        assertEquals(newDate, loaded.date());
    }

    @Test
    void updateReturnsTrueWhenARowChanged() {
        Expense original = lunch();
        store.add(original);

        assertTrue(store.update(original.edit(TEN, Category.OTHER, "dinner", original.date())));
    }

    @Test
    void updateReturnsFalseForAnUnknownId() {
        assertFalse(store.update(lunch()));
    }

    @Test
    void updateDoesNotChangeTheId() {
        Expense original = lunch();
        store.add(original);

        store.update(original.edit(TEN, Category.OTHER, "dinner", original.date()));

        assertTrue(store.findById(original.id()).isPresent());
        assertEquals(1, store.find(SEPTEMBER).size());
    }

    @Test
    void updateDoesNotChangeCreatedAt() {
        Expense original = lunch();
        store.add(original);

        store.update(original.edit(TEN, Category.OTHER, "dinner", original.date()));

        assertEquals(original.createdAt(), store.findById(original.id()).orElseThrow().createdAt());
    }

    // --- delete -------------------------------------------------------------

    @Test
    void deleteRemovesTheRow() {
        Expense e = lunch();
        store.add(e);

        store.delete(e.id());

        assertTrue(store.findById(e.id()).isEmpty());
    }

    @Test
    void deleteReturnsTrueWhenARowWasRemoved() {
        Expense e = lunch();
        store.add(e);

        assertTrue(store.delete(e.id()));
    }

    @Test
    void deleteReturnsFalseForAnUnknownId() {
        assertFalse(store.delete("nope"));
    }

    @Test
    void deleteIsIdempotentInEffect() {
        Expense e = lunch();
        store.add(e);

        assertTrue(store.delete(e.id()));
        assertFalse(store.delete(e.id()));
    }

    /** The one bug this sprint that destroys data rather than displaying it wrongly. */
    @Test
    void deleteLeavesOtherRowsAlone() {
        seed();
        String victim = store.find(SEPTEMBER).get(0).id();

        store.delete(victim);

        assertEquals(3, store.find(SEPTEMBER).size());
        assertEquals(2, store.find(ExpenseFilter.of(YearMonth.of(2025, 8))).size()
                + store.find(ExpenseFilter.of(YearMonth.of(2025, 10))).size());
    }

    private void insertRaw(String values) throws SQLException {
        try (Connection c = db.open();
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO expenses (id, amount_cents, category, description, spent_on, created_at) "
                    + "VALUES " + values);
        }
    }
}
