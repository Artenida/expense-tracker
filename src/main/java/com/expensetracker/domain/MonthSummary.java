package com.expensetracker.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the right-hand panel shows for one month: the answer to a question, not a
 * thing that exists. Sprint 11 computes it twice, in SQL and with streams, and asserts
 * the two are {@code equals} - so every definition below is pinned, not implied.
 *
 * <ul>
 *   <li>{@code totalBudgeted} is the sum of <em>every</em> configured budget limit,
 *       including categories with no spending this month.</li>
 *   <li>{@code average} is {@code totalSpent / entryCount}, or {@code 0.00} when
 *       {@code entryCount == 0}.</li>
 *   <li>{@code totals} holds every category that was spent in or has a budget (an
 *       untouched budget appears with {@code spent = 0.00}); categories with neither
 *       are omitted. Ordered by {@code spent} descending, then {@code category.name()}
 *       ascending - the second key makes ties deterministic, and a record compares
 *       lists in order.</li>
 * </ul>
 */
public record MonthSummary(
        YearMonth yearMonth,
        BigDecimal totalSpent,
        BigDecimal totalBudgeted,
        int entryCount,
        BigDecimal average,
        List<CategoryTotal> totals,
        Optional<Expense> largest) {

    public MonthSummary {
        Objects.requireNonNull(yearMonth, "yearMonth");
        Objects.requireNonNull(largest, "largest");
        if (entryCount < 0) {
            throw new IllegalArgumentException("entryCount < 0: " + entryCount);
        }
        // BigDecimal.ZERO has scale 0 and Money.fromCents(0) has scale 2; without this,
        // two empty months would not be equal.
        totalSpent = totalSpent.setScale(2, RoundingMode.HALF_UP);
        totalBudgeted = totalBudgeted.setScale(2, RoundingMode.HALF_UP);
        average = average.setScale(2, RoundingMode.HALF_UP);
        // The record field is final, but the list it points at would not be. The copy
        // also rejects null elements.
        totals = List.copyOf(totals);
    }

    /** A month with no expenses: zeroes, not an exception and not a null. */
    public static MonthSummary empty(YearMonth month) {
        return new MonthSummary(month, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                BigDecimal.ZERO, List.of(), Optional.empty());
    }
}
