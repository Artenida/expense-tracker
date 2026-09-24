package com.expensetracker.domain;

import java.util.Objects;
import java.util.OptionalLong;

public enum BudgetStatus {
    OK        ("status-ok",        "on track"),
    WARNING   ("status-warning",   "close"),
    EXCEEDED  ("status-exceeded",  "over"),
    NO_BUDGET ("status-no-budget", "no budget");

    private final String cssClass;
    private final String label;

    BudgetStatus(String cssClass, String label) {
        this.cssClass = cssClass;
        this.label = label;
    }

    public String cssClass() {
        return cssClass;
    }

    public String label() {
        return label;
    }

    /**
     * Decides the status from exact cent amounts.
     * <p>
     * The threshold comparison cross-multiplies rather than computing a percentage:
     * {@code spent * 100 >= limit * 80} is exact {@code long} arithmetic, so no
     * rounding can push a value that is just under 80% over the line. The percentage
     * is computed elsewhere, for display only.
     */
    public static BudgetStatus of(long spentCents, OptionalLong limitCents) {
        Objects.requireNonNull(limitCents, "limitCents");
        if (spentCents < 0) {
            throw new IllegalArgumentException("spentCents must not be negative: " + spentCents);
        }
        if (limitCents.isEmpty()) {
            return NO_BUDGET;
        }
        long limit = limitCents.getAsLong();
        if (limit <= 0) {
            throw new IllegalArgumentException("limitCents must be positive: " + limit);
        }
        if (spentCents >= limit) {
            return EXCEEDED;
        }
        if (spentCents * 100 >= limit * 80) {
            return WARNING;
        }
        return OK;
    }
}
