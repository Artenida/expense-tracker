package com.expensetracker.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * The single place where a currency amount changes representation: {@code BigDecimal}
 * in Java, integer cents in the database, {@code String} in a text field.
 *
 * <p>A namespace for functions, not a thing - {@code final} stops subclassing and the
 * private constructor stops instantiation.
 */
public final class Money {

    /**
     * Digits, optionally a point and one or two more digits. No sign, no grouping
     * separator, no exponent. Compiled once because sprint 17 matches on every
     * keystroke.
     */
    private static final Pattern AMOUNT = Pattern.compile("^\\d{1,9}(\\.\\d{1,2})?$");

    private Money() {
    }

    /**
     * Exact conversion to cents. {@code longValueExact} throws
     * {@link ArithmeticException} when anything remains after the point, which is the
     * check - callers reach this only with an amount Validation already approved, so a
     * throw means a bug upstream rather than bad user input.
     */
    public static long toCents(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    /**
     * Builds the value directly from its unscaled form: 2490 with scale 2 is 24.90.
     * Never divides by 100.0, which would route through a double.
     */
    public static BigDecimal fromCents(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    /**
     * Reads a typed amount. Accepts only the pinned format, so the same build behaves
     * identically on a European and a US machine.
     *
     * <p>Checks format, not business rules: {@code "0.00"} parses fine and is rejected
     * later by {@link Validation#amount(String)}.
     */
    public static BigDecimal parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("amount is required");
        }
        String trimmed = raw.trim();
        if (!AMOUNT.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "amount must be a number with at most two decimals, for example 24.90; was '"
                            + raw + "'");
        }
        // The regex guarantees at most two decimals, so there is nothing to round.
        // If that assumption ever breaks this line throws instead of rounding silently.
        return new BigDecimal(trimmed).setScale(2);
    }

    /**
     * The only place in the project where rounding happens. Rounding for display is
     * fine; rounding before a comparison or a sum is the sprint 02 bug.
     *
     * <p>{@code toPlainString} rather than {@code toString}, which can produce
     * scientific notation for some values.
     */
    public static String format(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
