package com.expensetracker.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BudgetTest {

    @Test
    void validBudgetIsCreated() {
        Budget b = Budget.of(Category.GROCERIES, new BigDecimal("400.00"));

        assertEquals(Category.GROCERIES, b.category());
        assertEquals(0, new BigDecimal("400.00").compareTo(b.monthlyLimit()));
        assertEquals(2, b.monthlyLimit().scale());
    }

    /** Matches the database's CHECK (limit_cents > 0). */
    @Test
    void zeroLimitIsRejected() {
        ValidationException thrown = assertThrows(ValidationException.class,
                () -> Budget.of(Category.OTHER, new BigDecimal("0.00")));

        assertEquals(List.of("monthly limit must be greater than zero"), thrown.errors());
    }

    @Test
    void negativeLimitIsRejected() {
        assertThrows(ValidationException.class,
                () -> Budget.of(Category.OTHER, new BigDecimal("-50.00")));
    }

    @Test
    void threeDecimalLimitIsRejected() {
        assertThrows(ValidationException.class,
                () -> Budget.of(Category.OTHER, new BigDecimal("12.345")));
    }

    @Test
    void nullCategoryIsRejected() {
        assertThrows(ValidationException.class,
                () -> Budget.of(null, new BigDecimal("100.00")));
    }

    @Test
    void limitCentsConverts() {
        assertEquals(95_000, Budget.of(Category.OTHER, new BigDecimal("950.00")).limitCents());
    }

    @Test
    void scaleDoesNotAffectEqualityOrHash() {
        Instant now = Instant.now();
        Budget a = Budget.restore(Category.OTHER, new BigDecimal("950"), now);
        Budget b = Budget.restore(Category.OTHER, new BigDecimal("950.00"), now);

        assertEquals(a, b);
        assertEquals(1, new HashSet<>(List.of(a, b)).size());
    }
}
