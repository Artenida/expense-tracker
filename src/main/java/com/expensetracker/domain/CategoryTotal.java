package com.expensetracker.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One category's line in the summary panel. A value, not a thing: two totals for the
 * same numbers are equal however they were computed.
 *
 * <p>Every {@code BigDecimal} is rescaled to 2 in the compact constructor, because the
 * generated {@code equals} uses {@code BigDecimal.equals}, which compares scale.
 */
public record CategoryTotal(
        Category category,
        BigDecimal spent,
        Optional<BigDecimal> limit,
        BigDecimal percentUsed,
        BudgetStatus status) {

    /** Runs before the fields are assigned: what the parameters hold at the end is what gets stored. */
    public CategoryTotal {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(status, "status");
        spent = spent.setScale(2, RoundingMode.HALF_UP);
        percentUsed = percentUsed.setScale(2, RoundingMode.HALF_UP);
        limit = limit.map(l -> l.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Cents in, {@code BigDecimal} out: callers hold cents (the database does), the
     * status decision needs cents, and only the displayed values need decimals.
     */
    public static CategoryTotal of(Category category, long spentCents, OptionalLong limitCents) {
        // Decided first, on exact cents. The percentage below is rounded, and nothing
        // is allowed to branch on a rounded value.
        BudgetStatus status = BudgetStatus.of(spentCents, limitCents);

        Optional<BigDecimal> limit = limitCents.isPresent()
                ? Optional.of(Money.fromCents(limitCents.getAsLong()))
                : Optional.empty();

        BigDecimal percentUsed = limitCents.isPresent()
                ? BigDecimal.valueOf(spentCents)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(limitCents.getAsLong()), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new CategoryTotal(category, Money.fromCents(spentCents), limit, percentUsed, status);
    }

    /**
     * The fill of a progress bar, 0.0 to 1.0. The one place a {@code double} touches
     * money correctly: it is no longer money but a fraction of a few hundred pixels.
     * Clamped, so an exceeded budget shows a full bar, never an over-full one.
     */
    public double progress() {
        return Math.min(1.0, percentUsed.doubleValue() / 100.0);
    }
}
