package com.expensetracker.store;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.CategorySpend;
import com.expensetracker.domain.Expense;
import com.expensetracker.domain.ExpenseFilter;
import com.expensetracker.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    // --- sprint 09: addAll, the happy path ----------------------------------

    private static final YearMonth IMPORT_MONTH = YearMonth.of(2025, 9);
    private static final LocalDate FROM = LocalDate.of(2025, 9, 1);
    private static final LocalDate TO = LocalDate.of(2025, 9, 30);

    @Test
    void addAllInsertsEveryRow() {
        List<Expense> batch = TestData.randomExpenses(100, IMPORT_MONTH, 1L);

        assertEquals(100, store.addAll(batch));

        assertEquals(100, countRows());
        assertTrue(batch.stream().allMatch(e -> store.findById(e.id()).isPresent()));
    }

    @Test
    void addAllReturnsZeroForAnEmptyList() {
        assertEquals(0, store.addAll(List.of()));
    }

    @Test
    void addAllReportsProgressForEveryRow() {
        List<Integer> reported = new ArrayList<>();

        store.addAll(TestData.randomExpenses(100, IMPORT_MONTH, 1L), reported::add, () -> false);

        assertEquals(IntStream.rangeClosed(1, 100).boxed().toList(), reported);
    }

    /** 1 200, not 1 000: a multiple of 500 hides a missing final executeBatch. */
    @Test
    void addAllCrossesTheBatchBoundary() {
        assertEquals(1_200, store.addAll(TestData.randomExpenses(1_200, IMPORT_MONTH, 3L)));

        assertEquals(1_200, countRows());
    }

    // --- addAll: rollback ---------------------------------------------------

    private List<Expense> batchWithADuplicate() {
        Expense duplicate = expense("9.00", Category.OTHER, "dup", "2025-09-02");
        List<Expense> batch = new ArrayList<>(TestData.randomExpenses(50, IMPORT_MONTH, 1L));
        batch.add(duplicate);
        batch.add(duplicate);             // same id twice -> PRIMARY KEY violation
        return batch;
    }

    /** countRows is the assertion that matters: "it threw" is also true of a partial insert. */
    @Test
    void aFailurePartwayThroughLeavesTheTableUnchanged() {
        store.add(expense("5.00", Category.OTHER, "existing", "2025-09-01"));

        assertThrows(StoreException.class, () -> store.addAll(batchWithADuplicate()));

        assertEquals(1, countRows());
    }

    /**
     * The failure lands after the first batch of 500 has already been sent. This driver
     * commits an open transaction on setAutoCommit(true), so an implementation that only
     * rolled back on SQLException would keep those 500 rows.
     */
    @Test
    void aFailureAfterABatchWasSentStillRollsBackEverything() {
        List<Expense> batch = new ArrayList<>(TestData.randomExpenses(700, IMPORT_MONTH, 4L));
        batch.add(batch.get(0));          // duplicate of the first row, at position 701

        assertThrows(StoreException.class, () -> store.addAll(batch));

        assertEquals(0, countRows());
    }

    /** Not a SQLException: the exception is the caller's own and is rethrown unchanged. */
    @Test
    void anExceptionFromTheProgressCallbackRollsBack() {
        List<Expense> batch = TestData.randomExpenses(1_000, IMPORT_MONTH, 5L);
        IllegalStateException boom = new IllegalStateException("progress bar failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> store.addAll(batch, i -> {
                    if (i == 600) {
                        throw boom;
                    }
                }, () -> false));

        assertSame(boom, thrown);
        assertEquals(0, countRows());
    }

    @Test
    void theExceptionCarriesTheSqlCause() {
        StoreException thrown = assertThrows(StoreException.class, () -> store.addAll(batchWithADuplicate()));

        assertInstanceOf(SQLException.class, thrown.getCause());
    }

    /** A leak on the error path is invisible once and fatal two hundred times. */
    @Test
    void theConnectionIsNotLeaked() {
        List<Expense> failing = batchWithADuplicate();
        for (int i = 0; i < 200; i++) {
            assertThrows(StoreException.class, () -> store.addAll(failing));
        }

        assertEquals(10, store.addAll(TestData.randomExpenses(10, IMPORT_MONTH, 6L)));
    }

    // --- addAll: cancellation -----------------------------------------------

    /** Cancel is driven by the progress count, not a timer, so it lands deterministically. */
    @Test
    void cancellingPartwayThroughLeavesTheTableUnchanged() {
        List<Expense> batch = TestData.randomExpenses(1_000, IMPORT_MONTH, 2L);
        AtomicInteger seen = new AtomicInteger();

        int inserted = store.addAll(batch, seen::set, () -> seen.get() >= 600);

        assertEquals(0, inserted);
        assertEquals(600, seen.get(), "cancelled after exactly 600 rows were bound");
        assertEquals(0, countRows());
    }

    @Test
    void cancellingBeforeTheFirstRowInsertsNothing() {
        AtomicInteger progressCalls = new AtomicInteger();

        int inserted = store.addAll(TestData.randomExpenses(10, IMPORT_MONTH, 2L),
                i -> progressCalls.incrementAndGet(), () -> true);

        assertEquals(0, inserted);
        assertEquals(0, progressCalls.get());
        assertEquals(0, countRows());
    }

    /** Cancel is polled before each row - once the last row is bound, it is too late. */
    @Test
    void aCompletedImportIgnoresALateCancel() {
        AtomicInteger seen = new AtomicInteger();

        int inserted = store.addAll(TestData.randomExpenses(100, IMPORT_MONTH, 2L),
                seen::set, () -> seen.get() >= 100);

        assertEquals(100, inserted);
        assertEquals(100, countRows());
    }

    // --- aggregates ---------------------------------------------------------

    @Test
    void totalsByCategorySumsCorrectly() {
        seed();

        CategorySpend groceries = store.totalsByCategory(FROM, TO).stream()
                .filter(t -> t.category() == Category.GROCERIES).findFirst().orElseThrow();

        assertEquals(6_490, groceries.spentCents());      // 24.90 + 40.00
        assertEquals(2, groceries.entryCount());
    }

    @Test
    void totalsByCategoryOmitsCategoriesWithNoSpending() {
        seed();

        assertEquals(3, store.totalsByCategory(FROM, TO).size());
    }

    @Test
    void totalsByCategoryIsOrderedByTotalDescending() {
        seed();

        assertEquals(List.of(Category.GROCERIES, Category.LEISURE, Category.TRANSPORT),
                store.totalsByCategory(FROM, TO).stream().map(CategorySpend::category).toList());
    }

    /**
     * Sprint 11 depends on this. Inserted in reverse alphabetical order, so neither
     * insertion order nor the category index can produce the right answer by accident.
     */
    @Test
    void totalsByCategoryTiesBreakByCategoryName() {
        store.add(expense("30.00", Category.TRANSPORT, "train", "2025-09-10"));
        store.add(expense("30.00", Category.HEALTH, "dentist", "2025-09-11"));
        store.add(expense("30.00", Category.EDUCATION, "book", "2025-09-12"));

        assertEquals(List.of(Category.EDUCATION, Category.HEALTH, Category.TRANSPORT),
                store.totalsByCategory(FROM, TO).stream().map(CategorySpend::category).toList());
    }

    /** Empty, not one row of NULL - GROUP BY with no rows has no groups. */
    @Test
    void totalsByCategoryIsEmptyForAnEmptyMonth() {
        seed();

        assertTrue(store.totalsByCategory(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 31)).isEmpty());
    }

    @Test
    void totalsByCategoryExcludesAdjacentMonths() {
        seed();

        List<CategorySpend> totals = store.totalsByCategory(FROM, TO);

        assertTrue(totals.stream().noneMatch(t -> t.category() == Category.HOUSING), "October leaked in");
        assertEquals(6_490, totals.get(0).spentCents(), "August's 99.00 groceries leaked in");
    }

    // --- top N --------------------------------------------------------------

    @Test
    void findTopReturnsTheLargestFirst() {
        seed();

        assertEquals(List.of("market", "shop", "cinema"),
                store.findTop(FROM, TO, 3).stream().map(Expense::description).toList());
    }

    @Test
    void findTopRespectsTheLimit() {
        store.addAll(TestData.randomExpenses(10, IMPORT_MONTH, 7L));

        assertEquals(5, store.findTop(FROM, TO, 5).size());
    }

    @Test
    void findTopReturnsEverythingWhenTheLimitExceedsTheRowCount() {
        store.addAll(TestData.randomExpenses(3, IMPORT_MONTH, 7L));

        assertEquals(3, store.findTop(FROM, TO, 5).size());
    }

    @Test
    void findTopIsScopedToTheDateRange() {
        seed();

        List<String> top = store.findTop(FROM, TO, 10).stream().map(Expense::description).toList();

        assertEquals(4, top.size());
        assertFalse(top.contains("august"), "the 99.00 August row is the largest overall");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void findTopRejectsANonPositiveLimit(int limit) {
        assertThrows(IllegalArgumentException.class, () -> store.findTop(FROM, TO, limit));
    }

    /** Same amount: the later date first, then the id. Checked twice against insertion order. */
    @Test
    void findTopBreaksTiesStably() {
        Instant at = Instant.parse("2025-09-01T12:00:00Z");
        store.add(Expense.restore("b", new BigDecimal("50.00"), Category.OTHER, "early b", LocalDate.of(2025, 9, 3), at));
        store.add(Expense.restore("a", new BigDecimal("50.00"), Category.OTHER, "early a", LocalDate.of(2025, 9, 3), at));
        store.add(Expense.restore("c", new BigDecimal("50.00"), Category.OTHER, "late", LocalDate.of(2025, 9, 20), at));

        List<String> order = store.findTop(FROM, TO, 3).stream().map(Expense::description).toList();

        assertEquals(List.of("late", "early b", "early a"), order);
        assertEquals(order, store.findTop(FROM, TO, 3).stream().map(Expense::description).toList());
    }

    // --- the performance fixture --------------------------------------------

    /**
     * Excluded from the normal run; run with {@code mvn test -Dgroups=slow -DexcludedGroups=}.
     * Loose bound on purpose: it catches forgetting the transaction (every row committing
     * alone), not small regressions. Writes to target/fixtures rather than the temp dir,
     * because sprint 15 needs the file afterwards.
     */
    @Test
    @Tag("slow")
    void fiftyThousandRowsImportInReasonableTime() throws IOException {
        Path file = Path.of("target", "fixtures", "expenses-50k.db");
        Files.deleteIfExists(file);
        Files.deleteIfExists(Path.of(file + "-wal"));
        Files.deleteIfExists(Path.of(file + "-shm"));
        Database fixture = new Database(file);
        new SchemaMigrator(fixture).migrate();
        JdbcExpenseStore fixtureStore = new JdbcExpenseStore(fixture);
        List<Expense> batch = TestData.randomExpenses(50_000, YearMonth.of(2025, 8), 42L);

        long start = System.nanoTime();
        assertEquals(50_000, fixtureStore.addAll(batch));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(50_000, fixtureStore.find(ExpenseFilter.of(YearMonth.of(2025, 8))).size());
        System.out.println("50 000 rows imported in " + millis + " ms into " + fixture.path());
        assertTrue(millis < 30_000, "import took " + millis + " ms");
    }

    private int countRows() {
        try (Connection c = db.open();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM expenses")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    private void insertRaw(String values) throws SQLException {
        try (Connection c = db.open();
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO expenses (id, amount_cents, category, description, spent_on, created_at) "
                    + "VALUES " + values);
        }
    }
}
