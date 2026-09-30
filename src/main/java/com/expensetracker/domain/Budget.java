package com.expensetracker.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A monthly spending limit for one category. No id: the category <em>is</em> the key,
 * so there is at most one budget per category.
 *
 * <p>Same shape as {@link Expense} - immutable, validated in the one private
 * constructor, built through named factories.
 */
public final class Budget {

    private final Category category;
    private final BigDecimal monthlyLimit;
    private final Instant updatedAt;

    private Budget(Category category, BigDecimal monthlyLimit, Instant updatedAt) {
        List<String> errors = new ArrayList<>();
        if (category == null) {
            errors.add("category is required");
        }
        if (monthlyLimit == null) {
            errors.add("monthly limit is required");
        } else {
            // Mirrors the database's CHECK (limit_cents > 0), so the table never gets
            // the chance to reject a row the domain let through.
            if (monthlyLimit.signum() <= 0) {
                errors.add("monthly limit must be greater than zero");
            }
            if (monthlyLimit.stripTrailingZeros().scale() > 2) {
                errors.add("monthly limit must have at most two decimals");
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        this.category = category;
        this.monthlyLimit = monthlyLimit.setScale(2, RoundingMode.UNNECESSARY);
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** A budget being set now. */
    public static Budget of(Category category, BigDecimal monthlyLimit) {
        return new Budget(category, monthlyLimit, Instant.now());
    }

    /** A budget read back from storage, with the timestamp it was saved under. */
    public static Budget restore(Category category, BigDecimal monthlyLimit, Instant updatedAt) {
        return new Budget(category, monthlyLimit, updatedAt);
    }

    public Category category() {
        return category;
    }

    public BigDecimal monthlyLimit() {
        return monthlyLimit;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** The store and sprint 05 both want cents; one conversion, here. */
    public long limitCents() {
        return Money.toCents(monthlyLimit);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Budget other)) {
            return false;
        }
        return category == other.category
                && monthlyLimit.compareTo(other.monthlyLimit) == 0
                && updatedAt.equals(other.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(category, limitCents(), updatedAt);
    }

    @Override
    public String toString() {
        return "Budget[" + category + " " + Money.format(monthlyLimit) + "/month]";
    }
}
