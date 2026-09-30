package com.expensetracker.service;

import com.expensetracker.domain.Budget;
import com.expensetracker.domain.BudgetStatus;
import com.expensetracker.domain.Category;
import com.expensetracker.domain.CategoryTotal;
import com.expensetracker.domain.Expense;
import com.expensetracker.domain.MonthSummary;
import com.expensetracker.store.Database;
import com.expensetracker.store.JdbcBudgetStore;
import com.expensetracker.store.JdbcExpenseStore;
import com.expensetracker.store.SchemaMigrator;
import com.expensetracker.store.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static com.expensetracker.domain.Category.EDUCATION;
import static com.expensetracker.domain.Category.GROCERIES;
import static com.expensetracker.domain.Category.HEALTH;
import static com.expensetracker.domain.Category.HOUSING;
import static com.expensetracker.domain.Category.LEISURE;
import static com.expensetracker.domain.Category.TRANSPORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Against a real database, not the fakes: {@code viaSql} is only worth comparing if the
 * {@code GROUP BY} it relies on actually runs.
 */
class SummaryServiceTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    @TempDir
    Path tempDir;

    private JdbcExpenseStore store;
    private JdbcBudgetStore budgets;
    private SummaryService service;

    @BeforeEach
    void setUp() {
        Database db = new Database(tempDir.resolve("test.db"));
        new SchemaMigrator(db).migrate();
        store = new JdbcExpenseStore(db);
        budgets = new JdbcBudgetStore(db);
        service = new SummaryService(store, budgets);
    }

    /**
     * Static, so it can feed a parameterised test before any instance exists: the
     * service is passed in at call time rather than captured.
     */
    static Stream<Arguments> implementations() {
        return Stream.of(
                Arguments.of("sql", (BiFunction<SummaryService, YearMonth, MonthSummary>) SummaryService::viaSql),
                Arguments.of("stream", (BiFunction<SummaryService, YearMonth, MonthSummary>) SummaryService::viaStream));
    }

    private static Expense expense(String amount, Category category, String description, String date) {
        return Expense.create(new BigDecimal(amount), category, description, LocalDate.parse(date));
    }

    /**
     * Worked out by hand, not by running the code:
     * <pre>
     * GROCERIES: 24.90 + 40.00 = 64.90  (2 entries)  budget 100.00
     * TRANSPORT: 12.00 + 52.90 = 64.90  (2 entries)  budget  60.00  - same total, on purpose
     * LEISURE:   15.00                  (1 entry)    no budget
     * HEALTH:     0.00                  (0 entries)  budget  50.00
     *
     * total 144.80 over 5 entries, average 28.96; budgeted 100 + 60 + 50 = 210.00
     * largest: the train, 52.90
     * order: GROCERIES, TRANSPORT (tie, by name), LEISURE, HEALTH
     * </pre>
     */
    private void seedSeptember() {
        store.addAll(List.of(
                expense("24.90", GROCERIES, "shop", "2026-09-15"),
                expense("40.00", GROCERIES, "market", "2026-09-01"),
                expense("12.00", TRANSPORT, "bus", "2026-09-15"),
                expense("52.90", TRANSPORT, "train", "2026-09-20"),
                expense("15.00", LEISURE, "cinema", "2026-09-30")));

        budgets.upsert(Budget.of(GROCERIES, new BigDecimal("100.00")));
        budgets.upsert(Budget.of(TRANSPORT, new BigDecimal("60.00")));
        budgets.upsert(Budget.of(HEALTH, new BigDecimal("50.00")));
    }

    private static CategoryTotal totalFor(MonthSummary summary, Category category) {
        return summary.totals().stream()
                .filter(ct -> ct.category() == category)
                .findFirst()
                .orElseThrow(() -> new AssertionError(category + " missing from " + summary.totals()));
    }

    // --- spec test 8: the two agree ------------------------------------------

    @Test
    void theSqlAndStreamSummariesAreIdentical() {
        seedSeptember();

        assertEquals(service.viaSql(SEPTEMBER), service.viaStream(SEPTEMBER));
    }

    /** July and August have budgets but no expenses; one side starts from an empty GROUP BY. */
    @ParameterizedTest
    @ValueSource(strings = {"2026-09", "2026-08", "2026-07", "2025-12"})
    void theTwoImplementationsAgree(String month) {
        seedSeptember();
        YearMonth ym = YearMonth.parse(month);

        assertEquals(service.viaSql(ym), service.viaStream(ym));
    }

    @Test
    void summariseUsesAnImplementationThatAgrees() {
        seedSeptember();

        assertEquals(service.viaStream(SEPTEMBER), service.summarise(SEPTEMBER));
    }

    // --- the components, checked against hand-worked numbers -----------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void totalSpentIsTheSumOfEveryExpense(String name, BiFunction<SummaryService, YearMonth, MonthSummary> impl) {
        seedSeptember();

        assertEquals(new BigDecimal("144.80"), impl.apply(service, SEPTEMBER).totalSpent());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void entryCountIsTheNumberOfExpenses(String name, BiFunction<SummaryService, YearMonth, MonthSummary> impl) {
        seedSeptember();

        assertEquals(5, impl.apply(service, SEPTEMBER).entryCount());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void averageIsTotalOverCount(String name, BiFunction<SummaryService, YearMonth, MonthSummary> impl) {
        seedSeptember();

        assertEquals(new BigDecimal("28.96"), impl.apply(service, SEPTEMBER).average());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void totalBudgetedSumsEveryConfiguredLimit(String name, BiFunction<SummaryService, YearMonth, MonthSummary> impl) {
        seedSeptember();

        // Includes HEALTH's 50.00, which nothing was spent against.
        assertEquals(new BigDecimal("210.00"), impl.apply(service, SEPTEMBER).totalBudgeted());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void largestIsTheBiggestSingleExpense(String name, BiFunction<SummaryService, YearMonth, MonthSummary> impl) {
        seedSeptember();

        Expense largest = impl.apply(service, SEPTEMBER).largest().orElseThrow();

        assertEquals(new BigDecimal("52.90"), largest.amount());
        assertEquals("train", largest.description());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void categoriesWithABudgetButNoSpendingAppear(String name, BiFunction<SummaryService, YearMonth, MonthSummary> impl) {
        seedSeptember();

        CategoryTotal health = totalFor(impl.apply(service, SEPTEMBER), HEALTH);

        assertEquals(new BigDecimal("0.00"), health.spent());
        assertEquals(BudgetStatus.OK, health.status());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void categoriesWithSpendingButNoBudgetAppear(String name, BiFunction<SummaryService, YearMonth, MonthSummary> impl) {
        seedSeptember();

        CategoryTotal leisure = totalFor(impl.apply(service, SEPTEMBER), LEISURE);

        assertEquals(new BigDecimal("15.00"), leisure.spent());
        assertEquals(BudgetStatus.NO_BUDGET, leisure.status());
        assertTrue(leisure.limit().isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void categoriesWithNeitherAreAbsent(String name, BiFunction<SummaryService, YearMonth, MonthSummary> impl) {
        seedSeptember();

        List<Category> present = impl.apply(service, SEPTEMBER).totals().stream()
                .map(CategoryTotal::category).toList();

        assertEquals(4, present.size());
        assertFalse(present.contains(HOUSING));
        assertFalse(present.contains(EDUCATION));
    }

    // --- ordering and ties ---------------------------------------------------

    /** If this passes for sql and fails for stream, the stream version is showing HashMap order. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void categoriesWithEqualSpendingAreOrderedByName(String name, BiFunction<SummaryService, YearMonth, MonthSummary> impl) {
        seedSeptember();

        List<Category> order = impl.apply(service, SEPTEMBER).totals().stream()
                .map(CategoryTotal::category).toList();

        // GROCERIES and TRANSPORT both 64.90 -> by name; then LEISURE 15.00, HEALTH 0.00
        assertEquals(List.of(GROCERIES, TRANSPORT, LEISURE, HEALTH), order);
    }

    @Test
    void tiedLargestExpensesResolveTheSameWayInBothImplementations() {
        store.addAll(List.of(
                expense("50.00", GROCERIES, "first", "2026-09-10"),
                expense("50.00", TRANSPORT, "second", "2026-09-20")));

        assertEquals(service.viaSql(SEPTEMBER).largest(), service.viaStream(SEPTEMBER).largest());
    }

    /** Asserts the specific expense, so a one-key comparator cannot pass by luck. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void onEqualAmountsTheLaterDateIsLargest(String name, BiFunction<SummaryService, YearMonth, MonthSummary> impl) {
        store.addAll(List.of(
                expense("50.00", TRANSPORT, "second", "2026-09-20"),
                expense("50.00", GROCERIES, "first", "2026-09-10")));

        assertEquals("second", impl.apply(service, SEPTEMBER).largest().orElseThrow().description());
    }

    /** The third key: same amount, same day. SQLite's BINARY collation and String.compareTo agree on ids. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void onEqualAmountsAndDatesTheHigherIdIsLargest(String name, BiFunction<SummaryService, YearMonth, MonthSummary> impl) {
        List<Expense> twins = List.of(
                expense("50.00", GROCERIES, "a", "2026-09-10"),
                expense("50.00", GROCERIES, "b", "2026-09-10"),
                expense("50.00", GROCERIES, "c", "2026-09-10"));
        store.addAll(twins);
        Expense expected = twins.stream().max(Comparator.comparing(Expense::id)).orElseThrow();

        assertEquals(expected, impl.apply(service, SEPTEMBER).largest().orElseThrow());
    }

    // --- spec test 10: the empty month ---------------------------------------

    @Test
    void anEmptyMonthReturnsZeroesViaSql() {
        MonthSummary summary = service.viaSql(SEPTEMBER);

        assertEquals(new BigDecimal("0.00"), summary.totalSpent());
        assertEquals(new BigDecimal("0.00"), summary.average());
        assertEquals(0, summary.entryCount());
    }

    @Test
    void anEmptyMonthReturnsZeroesViaStream() {
        MonthSummary summary = service.viaStream(SEPTEMBER);

        assertEquals(new BigDecimal("0.00"), summary.totalSpent());
        assertEquals(new BigDecimal("0.00"), summary.average());
        assertEquals(0, summary.entryCount());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void anEmptyMonthHasNoLargest(String name, BiFunction<SummaryService, YearMonth, MonthSummary> impl) {
        assertTrue(impl.apply(service, SEPTEMBER).largest().isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void anEmptyMonthWithBudgetsStillShowsThem(String name, BiFunction<SummaryService, YearMonth, MonthSummary> impl) {
        budgets.upsert(Budget.of(GROCERIES, new BigDecimal("100.00")));
        budgets.upsert(Budget.of(HEALTH, new BigDecimal("50.00")));

        MonthSummary summary = impl.apply(service, SEPTEMBER);

        // Both 0.00, so ordered by name.
        assertEquals(List.of(GROCERIES, HEALTH),
                summary.totals().stream().map(CategoryTotal::category).toList());
        assertTrue(summary.totals().stream().allMatch(ct -> ct.spent().equals(new BigDecimal("0.00"))));
        assertEquals(new BigDecimal("150.00"), summary.totalBudgeted());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void anEmptyMonthWithNoBudgetsHasNoTotals(String name, BiFunction<SummaryService, YearMonth, MonthSummary> impl) {
        assertTrue(impl.apply(service, SEPTEMBER).totals().isEmpty());
    }

    /** If these differ, one of them is wrong about what "empty" means. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("implementations")
    void anEmptyMonthEqualsMonthSummaryEmpty(String name, BiFunction<SummaryService, YearMonth, MonthSummary> impl) {
        assertEquals(MonthSummary.empty(SEPTEMBER), impl.apply(service, SEPTEMBER));
    }

    // --- R-2 boundaries, end to end ------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "79.99, OK",
            "80.00, WARNING",
            "99.99, WARNING",
            "100.00, EXCEEDED",
            "150.00, EXCEEDED"
    })
    void budgetStatusBoundariesSurviveTheRoundTrip(String spent, BudgetStatus expected) {
        budgets.upsert(Budget.of(GROCERIES, new BigDecimal("100.00")));
        store.add(expense(spent, GROCERIES, "test", "2026-09-15"));

        CategoryTotal total = service.summarise(SEPTEMBER).totals().get(0);

        assertEquals(expected, total.status());
    }

    // --- timing, for the README ----------------------------------------------

    /** A measurement, not an assertion. Run with -Dgroups=slow -DexcludedGroups= */
    @Test
    @Tag("slow")
    void compareBothImplementationsOnFiftyThousandRows() {
        YearMonth month = YearMonth.of(2026, 8);
        store.addAll(TestData.randomExpenses(50_000, month, 42L));
        for (Category category : Category.values()) {
            budgets.upsert(Budget.of(category, new BigDecimal("5000.00")));
        }

        for (int i = 0; i < 3; i++) {                        // warm up the JIT and the page cache
            service.viaSql(month);
            service.viaStream(month);
        }

        long sql = medianMillis(() -> service.viaSql(month));
        long stream = medianMillis(() -> service.viaStream(month));

        assertEquals(service.viaSql(month), service.viaStream(month));
        System.out.printf("summary over 50 000 rows: sql=%d ms  stream=%d ms%n", sql, stream);
    }

    private static long medianMillis(Runnable work) {
        long[] runs = new long[5];
        for (int i = 0; i < runs.length; i++) {
            long start = System.nanoTime();
            work.run();
            runs[i] = (System.nanoTime() - start) / 1_000_000;
        }
        Arrays.sort(runs);
        return runs[runs.length / 2];
    }
}
