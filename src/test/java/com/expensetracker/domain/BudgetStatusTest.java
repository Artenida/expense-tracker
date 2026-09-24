package com.expensetracker.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BudgetStatusTest {

    @ParameterizedTest
    @CsvSource({
        " 7999, OK",
        " 8000, WARNING",
        " 9999, WARNING",
        "10000, EXCEEDED",
        "15000, EXCEEDED",
        "    0, OK"
    })
    void thresholds(long spentCents, BudgetStatus expected) {
        assertEquals(expected, BudgetStatus.of(spentCents, OptionalLong.of(10_000)));
    }

    /**
     * 79.996% of the limit. Computing a percentage and rounding HALF_UP turns this
     * into 80.00 and reports WARNING; cross-multiplying keeps it exact.
     */
    @Test
    void aValueJustBelowTheThresholdIsNotWarning() {
        assertEquals(BudgetStatus.OK, BudgetStatus.of(79_996, OptionalLong.of(100_000)));
    }

    @Test
    void noLimitMeansNoBudget() {
        assertEquals(BudgetStatus.NO_BUDGET, BudgetStatus.of(5_000, OptionalLong.empty()));
        assertEquals(BudgetStatus.NO_BUDGET, BudgetStatus.of(0, OptionalLong.empty()));
    }

    @Test
    void zeroLimitIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BudgetStatus.of(0, OptionalLong.of(0)));
    }

    @Test
    void negativeLimitIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BudgetStatus.of(0, OptionalLong.of(-1)));
    }

    @Test
    void negativeSpendIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BudgetStatus.of(-1, OptionalLong.of(100)));
    }

    @Test
    void everyStatusHasADistinctCssClass() {
        Set<String> classes = Arrays.stream(BudgetStatus.values())
                .map(BudgetStatus::cssClass)
                .collect(Collectors.toSet());

        assertEquals(BudgetStatus.values().length, classes.size(),
                "two statuses share a css class: " + classes);
    }
}
