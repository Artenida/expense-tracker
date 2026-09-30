package com.expensetracker.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

/**
 * What the left-hand side of the window asks for, and what the store answers. One
 * object rather than loose parameters, so adding a filter later changes one record
 * instead of five signatures.
 *
 * <p>Holds a {@code YearMonth} rather than two dates, so a range that is not a whole
 * month cannot be expressed. An empty {@code category} is the combo box's "All".
 */
public record ExpenseFilter(YearMonth month, Optional<Category> category) {

    public ExpenseFilter {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(category, "category");
    }

    /** Every category. */
    public static ExpenseFilter of(YearMonth month) {
        return new ExpenseFilter(month, Optional.empty());
    }

    public static ExpenseFilter of(YearMonth month, Category category) {
        return new ExpenseFilter(month, Optional.of(category));
    }

    /** Inclusive, like SQL's {@code BETWEEN}. */
    public LocalDate from() {
        return month.atDay(1);
    }

    /** Inclusive. The JDK knows about February and leap years, so no caller has to. */
    public LocalDate to() {
        return month.atEndOfMonth();
    }
}
