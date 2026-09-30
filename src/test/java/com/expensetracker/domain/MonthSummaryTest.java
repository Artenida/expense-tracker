package com.expensetracker.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonthSummaryTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    // --- the empty case: spec test 10 ---------------------------------------

    /** compareTo, not assertEquals: the constructor rescaled ZERO to 0.00. */
    @Test
    void emptyMonthIsZeroesNotAnException() {
        MonthSummary summary = MonthSummary.empty(SEPTEMBER);

        assertEquals(0, summary.entryCount());
        assertEquals(0, summary.totalSpent().compareTo(BigDecimal.ZERO));
        assertEquals(0, summary.totalBudgeted().compareTo(BigDecimal.ZERO));
        assertEquals(0, summary.average().compareTo(BigDecimal.ZERO));
        assertTrue(summary.totals().isEmpty());
        assertTrue(summary.largest().isEmpty());
    }

    // --- the normalisation that makes sprint 11 work ------------------------

    /** Delete the setScale lines and this fails: spec test 8, thirteen sprints early. */
    @Test
    void summariesAreEqualRegardlessOfHowTheBigDecimalsWereBuilt() {
        // as the stream implementation would build it - ZERO has scale 0
        MonthSummary a = new MonthSummary(SEPTEMBER, BigDecimal.ZERO, BigDecimal.ZERO,
                0, BigDecimal.ZERO, List.of(), Optional.empty());

        // as the SQL implementation would build it - from cents, scale 2
        MonthSummary b = new MonthSummary(SEPTEMBER, Money.fromCents(0), Money.fromCents(0),
                0, Money.fromCents(0), List.of(), Optional.empty());

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // --- defensive copying --------------------------------------------------

    @Test
    void mutatingTheSourceListDoesNotAffectTheSummary() {
        List<CategoryTotal> source = new ArrayList<>();
        source.add(CategoryTotal.of(Category.OTHER, 1_000, OptionalLong.empty()));

        MonthSummary summary = new MonthSummary(SEPTEMBER, BigDecimal.TEN, BigDecimal.ZERO,
                1, BigDecimal.TEN, source, Optional.empty());

        source.clear();
        assertEquals(1, summary.totals().size());
    }

    @Test
    void theReturnedListCannotBeModified() {
        MonthSummary summary = MonthSummary.empty(SEPTEMBER);

        assertThrows(UnsupportedOperationException.class, () -> summary.totals().add(null));
    }

    // --- validation ---------------------------------------------------------

    @Test
    void negativeEntryCountIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new MonthSummary(SEPTEMBER, BigDecimal.ZERO, BigDecimal.ZERO,
                        -1, BigDecimal.ZERO, List.of(), Optional.empty()));
    }

    @Test
    void nullLargestIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new MonthSummary(SEPTEMBER, BigDecimal.ZERO, BigDecimal.ZERO,
                        0, BigDecimal.ZERO, List.of(), null));
    }

    /** The generated toString names every component - you will read it in failure output. */
    @Test
    void toStringIsReadable() {
        String text = MonthSummary.empty(SEPTEMBER).toString();

        assertTrue(text.startsWith("MonthSummary["), text);
        for (String component : List.of("yearMonth=2026-09", "totalSpent=0.00",
                "totalBudgeted=0.00", "entryCount=0", "average=0.00", "totals=[]",
                "largest=Optional.empty")) {
            assertTrue(text.contains(component), () -> "missing " + component + " in " + text);
        }
    }
}
