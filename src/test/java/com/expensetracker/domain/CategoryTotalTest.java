package com.expensetracker.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoryTotalTest {

    private static CategoryTotal groceries() {
        return CategoryTotal.of(Category.GROCERIES, 23_640, OptionalLong.of(95_000));
    }

    @Test
    void ofComputesSpentAndLimit() {
        CategoryTotal total = groceries();

        assertEquals(0, new BigDecimal("236.40").compareTo(total.spent()));
        assertEquals(0, new BigDecimal("950.00").compareTo(total.limit().orElseThrow()));
    }

    /** By hand: 23640 x 100 / 95000 = 24.884... -> 24.88. */
    @Test
    void ofComputesPercentUsed() {
        assertEquals(0, new BigDecimal("24.88").compareTo(groceries().percentUsed()));
    }

    @Test
    void noLimitGivesEmptyOptionalAndZeroPercent() {
        CategoryTotal total = CategoryTotal.of(Category.OTHER, 1_000, OptionalLong.empty());

        assertTrue(total.limit().isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(total.percentUsed()));
        assertEquals(BudgetStatus.NO_BUDGET, total.status());
    }

    @Test
    void progressIsClampedAtOne() {
        CategoryTotal total = CategoryTotal.of(Category.OTHER, 15_000, OptionalLong.of(10_000));

        assertEquals(1.0, total.progress());
    }

    @Test
    void progressIsZeroWithNoBudget() {
        CategoryTotal total = CategoryTotal.of(Category.OTHER, 1_000, OptionalLong.empty());

        assertEquals(0.0, total.progress());
    }

    @Test
    void allBigDecimalsHaveScaleTwo() {
        CategoryTotal total = CategoryTotal.of(Category.OTHER, 1_000, OptionalLong.empty());

        assertEquals(2, total.spent().scale());
        assertEquals(2, total.percentUsed().scale(), "BigDecimal.ZERO must be rescaled");
        assertEquals(2, groceries().limit().orElseThrow().scale());
    }

    @Test
    void statusIsDecidedOnCents() {
        CategoryTotal total = CategoryTotal.of(Category.OTHER, 8_000, OptionalLong.of(10_000));

        assertEquals(BudgetStatus.WARNING, total.status());
    }
}
