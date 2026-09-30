package com.expensetracker.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CategorySpendTest {

    @Test
    void holdsWhatItWasGiven() {
        CategorySpend spend = new CategorySpend(Category.GROCERIES, 6_490, 2);

        assertEquals(Category.GROCERIES, spend.category());
        assertEquals(6_490, spend.spentCents());
        assertEquals(2, spend.entryCount());
    }

    @Test
    void nullCategoryIsRejected() {
        assertThrows(NullPointerException.class, () -> new CategorySpend(null, 0, 0));
    }

    @Test
    void negativeSpendIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CategorySpend(Category.OTHER, -1, 1));
    }

    @Test
    void negativeCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CategorySpend(Category.OTHER, 0, -1));
    }

    /** A record: two rows with the same numbers are the same answer. */
    @Test
    void equalValuesAreEqual() {
        assertEquals(new CategorySpend(Category.OTHER, 100, 1), new CategorySpend(Category.OTHER, 100, 1));
    }
}
