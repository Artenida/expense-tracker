package com.expensetracker.ui;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.Expense;
import com.expensetracker.domain.Money;
import com.expensetracker.domain.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the dialog depends on, with no toolkit: the rules that gate Save, and the
 * create-or-edit decision in the result converter.
 */
class ExpenseDialogResultTest {

    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_2 = LocalDate.of(2026, 9, 2);

    // --- spec test 14, at the dialog's boundary -----------------------------

    /** Restates sprint 03 on purpose: if Validation.amount is relaxed, the dialog's behaviour changed too. */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "", "1.234", "-2", "12,50"})
    void badlyFormattedAmountsProduceAMessageAndDoNotParse(String raw) {
        assertFalse(Validation.amount(raw).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> Money.parse(raw));
    }

    /**
     * Not in the format list above, because "0" parses: Money.parse checks format, and
     * zero is a business rule. That is why Save is gated on Validation, not on parse
     * succeeding - gated on parse, the dialog would hand Expense.create a zero, and the
     * domain would throw.
     */
    @Test
    void zeroParsesButIsStillRejected() {
        assertDoesNotThrow(() -> Money.parse("0"));
        assertEquals(1, Validation.amount("0").size());
        assertEquals("amount must be greater than zero", Validation.amount("0").get(0));
    }

    // --- the result converter -----------------------------------------------

    @Test
    void editingKeepsTheIdAndCreatedAt() {
        Expense original = Expense.create(new BigDecimal("10.00"), Category.OTHER, "lunch", SEP_1);

        Expense edited = ExpenseDialog.result(original, "12.00", Category.GROCERIES, "brunch", SEP_2);

        assertEquals(original.id(), edited.id());
        assertEquals(original.createdAt(), edited.createdAt());
        assertEquals(0, new BigDecimal("12.00").compareTo(edited.amount()));
        assertEquals(Category.GROCERIES, edited.category());
        assertEquals("brunch", edited.description());
        assertEquals(SEP_2, edited.date());
    }

    @Test
    void addingMakesANewExpense() {
        Expense other = Expense.create(new BigDecimal("10.00"), Category.OTHER, "lunch", SEP_1);

        Expense added = ExpenseDialog.result(null, "24.90", Category.GROCERIES, "Weekly shop", SEP_1);

        assertNotEquals(other.id(), added.id());
        assertEquals(0, new BigDecimal("24.90").compareTo(added.amount()));
    }

    /** "Untouched fields keep their values": re-saving the pre-filled text changes nothing. */
    @Test
    void savingThePrefilledValuesUnchangedGivesAnEqualExpense() {
        Expense original = Expense.create(new BigDecimal("24.9"), Category.HEALTH, "Pharmacy", SEP_1);

        Expense resaved = ExpenseDialog.result(original, Money.format(original.amount()),
                original.category(), original.description(), original.date());

        assertEquals(original, resaved);
        assertEquals(original.amount(), resaved.amount());
    }
}
