package com.expensetracker.service;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.Expense;
import com.expensetracker.domain.ExpenseFilter;
import com.expensetracker.domain.ValidationException;
import com.expensetracker.store.Database;
import com.expensetracker.store.JdbcExpenseStore;
import com.expensetracker.store.SchemaMigrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs against an in-memory fake: no file, no migration, no JDBC. One integration test at the end. */
class ExpenseServiceTest {

    private static final BigDecimal TEN = new BigDecimal("10.00");
    private static final LocalDate TODAY = LocalDate.now();

    private FakeExpenseStore fake;
    private ExpenseService service;

    @BeforeEach
    void setUp() {
        fake = new FakeExpenseStore();
        service = new ExpenseService(fake);
    }

    private Expense addLunch() {
        return service.add(TEN, Category.OTHER, "lunch", TODAY);
    }

    // --- add ----------------------------------------------------------------

    @Test
    void addCreatesAndStores() {
        Expense added = addLunch();

        assertFalse(added.id().isBlank());
        assertTrue(fake.findById(added.id()).isPresent());
    }

    @Test
    void addReturnsTheCreatedExpense() {
        Expense added = service.add(new BigDecimal("24.90"), Category.GROCERIES, "shop", TODAY);

        assertEquals(List.of(added), fake.rows());
        assertEquals("shop", added.description());
    }

    /** Both halves: the throw alone would also happen if the service stored first and validated after. */
    @Test
    void addRejectsAnInvalidAmountAndStoresNothing() {
        assertThrows(ValidationException.class,
                () -> service.add(new BigDecimal("-5.00"), Category.OTHER, "bad", TODAY));

        assertTrue(fake.isEmpty());
    }

    @Test
    void addRejectsAFutureDateAndStoresNothing() {
        assertThrows(ValidationException.class,
                () -> service.add(TEN, Category.OTHER, "later", TODAY.plusDays(1)));

        assertTrue(fake.isEmpty());
    }

    // --- reads --------------------------------------------------------------

    @Test
    void findDelegatesToTheStore() {
        ExpenseFilter filter = ExpenseFilter.of(YearMonth.of(2025, 9), Category.HEALTH);
        fake.queryResult = List.of(addLunch());

        List<Expense> found = service.find(filter);

        assertSame(filter, fake.lastFilter, "the filter arrives unchanged");
        assertSame(fake.queryResult, found, "and the result comes back unchanged");
    }

    @Test
    void findByIdReturnsEmptyForUnknown() {
        assertTrue(service.findById("nope").isEmpty());
    }

    /** The service's one piece of translation on a read: a month becomes two inclusive dates. */
    @Test
    void topExpensesTurnsTheMonthIntoItsFirstAndLastDay() {
        service.topExpenses(YearMonth.of(2024, 2), 5);

        assertEquals(LocalDate.of(2024, 2, 1), fake.lastTopFrom);
        assertEquals(LocalDate.of(2024, 2, 29), fake.lastTopTo);
        assertEquals(5, fake.lastTopLimit);
    }

    // --- error translation: the point of the sprint -------------------------

    @Test
    void deleteSucceedsForAnExistingExpense() {
        Expense e = addLunch();

        service.delete(e.id());

        assertTrue(fake.isEmpty());
    }

    /** id() is what sprint 18 uses; the message is what the log gets. Both are the contract. */
    @Test
    void deletingAnUnknownExpenseThrowsWithTheId() {
        ExpenseNotFoundException thrown = assertThrows(ExpenseNotFoundException.class,
                () -> service.delete("does-not-exist"));

        assertEquals("does-not-exist", thrown.id());
        assertTrue(thrown.getMessage().contains("does-not-exist"), thrown::getMessage);
    }

    @Test
    void updatingAnUnknownExpenseThrows() {
        Expense neverStored = Expense.create(TEN, Category.OTHER, "ghost", TODAY);

        ExpenseNotFoundException thrown = assertThrows(ExpenseNotFoundException.class,
                () -> service.update(neverStored));

        assertEquals(neverStored.id(), thrown.id());
    }

    @Test
    void updateKeepsTheIdAndCreatedAt() {
        Expense original = addLunch();

        service.update(original.edit(new BigDecimal("12.00"), Category.HEALTH, "pharmacy", TODAY));

        Expense stored = service.findById(original.id()).orElseThrow();
        assertEquals(original.createdAt(), stored.createdAt());
        assertEquals("pharmacy", stored.description());
    }

    /** The specification's requirement, not an abstract edge case. */
    @Test
    void deletingARowAnotherProcessAlreadyRemovedIsReported() {
        Expense e = addLunch();

        fake.removeDirectly(e.id());

        assertThrows(ExpenseNotFoundException.class, () -> service.delete(e.id()));
    }

    // --- the one integration test -------------------------------------------

    /**
     * Proves the pieces fit - the fake and the real store could disagree, and a suite of
     * only fake-backed tests would stay green. 2025, because the date must be in the past.
     */
    @Test
    void serviceAndJdbcStoreWorkTogether(@TempDir Path tempDir) {
        Database db = new Database(tempDir.resolve("integration.db"));
        new SchemaMigrator(db).migrate();
        ExpenseService real = new ExpenseService(new JdbcExpenseStore(db));
        ExpenseFilter september = ExpenseFilter.of(YearMonth.of(2025, 9));

        Expense added = real.add(new BigDecimal("24.90"), Category.GROCERIES, "shop", LocalDate.of(2025, 9, 15));
        assertEquals(1, real.find(september).size());

        real.delete(added.id());
        assertTrue(real.find(september).isEmpty());
        assertThrows(ExpenseNotFoundException.class, () -> real.delete(added.id()));
    }
}
