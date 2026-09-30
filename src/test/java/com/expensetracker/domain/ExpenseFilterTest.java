package com.expensetracker.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The February tests look like they test the JDK, and in a sense they do. They document
 * why ExpenseFilter owns the bounds instead of each caller computing its own.
 */
class ExpenseFilterTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    @Test
    void fromIsTheFirstOfTheMonth() {
        assertEquals(LocalDate.of(2026, 9, 1), ExpenseFilter.of(SEPTEMBER).from());
    }

    @Test
    void toIsTheLastOfTheMonth() {
        assertEquals(LocalDate.of(2026, 9, 30), ExpenseFilter.of(SEPTEMBER).to());
    }

    @Test
    void februaryInALeapYear() {
        assertEquals(LocalDate.of(2024, 2, 29), ExpenseFilter.of(YearMonth.of(2024, 2)).to());
    }

    @Test
    void februaryInANonLeapYear() {
        assertEquals(LocalDate.of(2026, 2, 28), ExpenseFilter.of(YearMonth.of(2026, 2)).to());
    }

    @Test
    void singleArgFactoryMeansAllCategories() {
        assertTrue(ExpenseFilter.of(SEPTEMBER).category().isEmpty());
    }

    @Test
    void twoArgFactoryCarriesTheCategory() {
        assertEquals(Optional.of(Category.GROCERIES),
                ExpenseFilter.of(SEPTEMBER, Category.GROCERIES).category());
    }

    @Test
    void nullCategoryOptionalIsRejected() {
        assertThrows(NullPointerException.class, () -> new ExpenseFilter(SEPTEMBER, null));
    }

    /** Records compare by value: same question, equal filters. */
    @Test
    void sameMonthAndCategoryAreEqual() {
        assertEquals(ExpenseFilter.of(SEPTEMBER, Category.HEALTH),
                ExpenseFilter.of(YearMonth.of(2026, 9), Category.HEALTH));
    }
}
