package com.expensetracker.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The rules, written once, in a form that <em>reports</em>. The dialog paints the
 * messages next to a field; the domain turns the same report into a throw. One rule,
 * two reactions.
 *
 * <p>Contract for every method: an empty list means valid. Never null, never a boolean,
 * never a thrown exception.
 */
public final class Validation {

    private static final int MAX_DESCRIPTION_LENGTH = 100;

    private Validation() {
    }

    public static List<String> amount(String raw) {
        List<String> errors = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            errors.add("amount is required");
            return errors;
        }

        BigDecimal parsed;
        try {
            // Delegated rather than re-written: two copies of the regex would be two
            // things to keep in step.
            parsed = Money.parse(raw);
        } catch (IllegalArgumentException e) {
            errors.add("amount must be a number with at most two decimals, for example 24.90");
            return errors;
        }

        if (parsed.signum() <= 0) {
            errors.add("amount must be greater than zero");
        }
        return errors;
    }

    public static List<String> description(String raw) {
        List<String> errors = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            errors.add("description is required");
            return errors;
        }

        int length = raw.trim().length();
        if (length > MAX_DESCRIPTION_LENGTH) {
            errors.add("description must be " + MAX_DESCRIPTION_LENGTH
                    + " characters or fewer (was " + length + ")");
        }
        return errors;
    }

    /**
     * Reads the system clock, which makes "is this in the future" untestable at a fixed
     * point in time. The proper fix is an injected {@link java.time.Clock}; the only
     * rule here is stable enough not to need one.
     */
    public static List<String> date(LocalDate date) {
        List<String> errors = new ArrayList<>();
        if (date == null) {
            errors.add("date is required");
            return errors;
        }

        if (date.isAfter(LocalDate.now())) {
            errors.add("date cannot be in the future");
        }
        return errors;
    }
}
