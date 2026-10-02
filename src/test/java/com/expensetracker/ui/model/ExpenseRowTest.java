package com.expensetracker.ui.model;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.Expense;
import javafx.beans.property.ReadOnlyStringProperty;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Needs no JavaFX toolkit: a {@code ReadOnlyStringWrapper} is a plain object until it is in a scene. */
class ExpenseRowTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 15);
    private static final Instant CREATED = Instant.parse("2026-09-15T10:00:00Z");

    private static Expense expense(String amount, Category category) {
        return Expense.restore("id-1", new BigDecimal(amount), category, "Weekly shop", DATE, CREATED);
    }

    private static Expense someExpense() {
        return expense("24.90", Category.GROCERIES);
    }

    // --- spec test 12 -------------------------------------------------------

    @Test
    void expenseRowReflectsTheExpenseItWraps() {
        ExpenseRow row = new ExpenseRow(expense("24.9", Category.GROCERIES));

        assertEquals("2026-09-15", row.dateProperty().get());       // ISO
        assertEquals("Groceries", row.categoryProperty().get());    // display name
        assertEquals("Weekly shop", row.descriptionProperty().get());
        assertEquals("24.90", row.amountProperty().get());          // two decimals
    }

    // --- formatting ---------------------------------------------------------

    @Test
    void wholeAmountsShowTwoDecimals() {
        assertEquals("24.00", new ExpenseRow(expense("24", Category.GROCERIES)).amountProperty().get());
    }

    @Test
    void singleDecimalIsPadded() {
        assertEquals("24.90", new ExpenseRow(expense("24.9", Category.GROCERIES)).amountProperty().get());
    }

    @Test
    void largeAmountsAreNotAbbreviated() {
        assertEquals("1234567.89",
                new ExpenseRow(expense("1234567.89", Category.GROCERIES)).amountProperty().get());
    }

    @Test
    void categoryUsesDisplayNameNotEnumName() {
        String shown = new ExpenseRow(expense("1", Category.GROCERIES)).categoryProperty().get();

        assertEquals("Groceries", shown);
        assertFalse(shown.equals(Category.GROCERIES.name()));
    }

    @Test
    void everyCategoryFormats() {
        for (Category c : Category.values()) {
            String shown = new ExpenseRow(expense("1", c)).categoryProperty().get();

            assertEquals(c.displayName(), shown);
            assertFalse(shown.isBlank(), c.name() + " has a blank display name");
        }
    }

    // --- the typed amount -------------------------------------------------

    @Test
    void amountValueIsTheBigDecimalItself() {
        ExpenseRow row = new ExpenseRow(expense("24.9", Category.GROCERIES));

        assertEquals(new BigDecimal("24.90"), row.amountValueProperty().get());
    }

    /** What the table's comparator does with the typed column: numeric, not alphabetical. */
    @Test
    void amountValuesSortNumerically() {
        List<String> sorted = ExpenseRow.wrap(List.of(
                        expense("100.00", Category.OTHER),
                        expense("24.90", Category.OTHER),
                        expense("9.00", Category.OTHER)))
                .stream()
                .sorted(java.util.Comparator.comparing(r -> r.amountValueProperty().get()))
                .map(r -> r.amountProperty().get())
                .toList();

        assertEquals(List.of("9.00", "24.90", "100.00"), sorted);
    }

    // --- the source is preserved --------------------------------------------

    @Test
    void sourceReturnsTheOriginalExpense() {
        Expense expense = someExpense();
        assertSame(expense, new ExpenseRow(expense).source());
    }

    @Test
    void nullExpenseIsRejected() {
        assertThrows(NullPointerException.class, () -> new ExpenseRow(null));
    }

    @Test
    void propertiesAreReadOnly() throws NoSuchMethodException {
        for (String name : List.of("dateProperty", "categoryProperty",
                "descriptionProperty", "amountProperty")) {
            assertEquals(ReadOnlyStringProperty.class,
                    ExpenseRow.class.getMethod(name).getReturnType(),
                    name + " must declare ReadOnlyStringProperty");
        }
    }

    @Test
    void wrapPreservesOrder() {
        Expense newest = Expense.restore("c", BigDecimal.ONE, Category.HOUSING, "c", DATE, CREATED);
        Expense middle = Expense.restore("a", BigDecimal.ONE, Category.HOUSING, "a", DATE.minusDays(1), CREATED);
        Expense oldest = Expense.restore("b", BigDecimal.ONE, Category.HOUSING, "b", DATE.minusDays(2), CREATED);

        List<ExpenseRow> rows = ExpenseRow.wrap(List.of(newest, middle, oldest));

        assertEquals(List.of(newest, middle, oldest), rows.stream().map(ExpenseRow::source).toList());
    }

    @Test
    void wrapOfEmptyIsEmpty() {
        assertTrue(ExpenseRow.wrap(List.of()).isEmpty());
    }
}
