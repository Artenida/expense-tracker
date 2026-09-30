package com.expensetracker.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    // --- arithmetic ---------------------------------------------------------

    /** Spec test 1: the reason money is not a double. */
    @Test
    void bigDecimalArithmeticIsExact() {
        BigDecimal sum = new BigDecimal("0.1").add(new BigDecimal("0.2"));

        assertEquals(0, sum.compareTo(new BigDecimal("0.30")),
                () -> "expected 0.30 but was " + sum);
    }

    // --- toCents / fromCents ------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "  24.90,     2490",
        "   0.05,        5",
        "1000.00,   100000",
        "   0.00,        0"
    })
    void toCentsConvertsWholeAndFractional(String amount, long expectedCents) {
        assertEquals(expectedCents, Money.toCents(new BigDecimal(amount)));
    }

    @Test
    void fromCentsProducesScaleTwo() {
        BigDecimal amount = Money.fromCents(2490);

        assertEquals(0, amount.compareTo(new BigDecimal("24.90")));
        assertEquals(2, amount.scale(), "money is always carried at scale 2");
    }

    @Test
    void threeDecimalsAreRejected() {
        assertThrows(ArithmeticException.class,
                () -> Money.toCents(new BigDecimal("24.905")));
    }

    /**
     * Spec test 2, written as a property: rather than checking known inputs, it asserts
     * a relationship that must hold for all of them. The seed is fixed so a failure is
     * reproducible; the message is a lambda so 100 passing iterations build no strings.
     */
    @Test
    void amountsSurviveARoundTrip() {
        Random random = new Random(20260922);

        for (int i = 0; i < 100; i++) {
            long cents = random.nextInt(1, 100_000_000);
            BigDecimal amount = Money.fromCents(cents);

            assertEquals(cents, Money.toCents(amount),
                    () -> "round trip lost precision for " + cents);
        }
    }

    // --- parse --------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "'24.90',   24.90",
        "'24.9',    24.90",
        "'24',      24.00",
        "'  24.90  ', 24.90",
        "'0.00',     0.00"
    })
    void parseAcceptsThePinnedFormat(String raw, String expected) {
        BigDecimal parsed = Money.parse(raw);

        assertEquals(0, parsed.compareTo(new BigDecimal(expected)),
                () -> "expected " + expected + " but was " + parsed);
        assertEquals(2, parsed.scale(), "parse always returns scale 2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.234", "12,50", "-2", "abc", "", "   ", "1e5", "1E5",
                            "24.", ".90", "1 000", "+2", "1234567890"})
    void parseRejectsAnythingElse(String raw) {
        assertThrows(IllegalArgumentException.class, () -> Money.parse(raw),
                () -> "should have rejected '" + raw + "'");
    }

    @Test
    void parseRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> Money.parse(null));
    }

    // --- format -------------------------------------------------------------

    @Test
    void formatAlwaysShowsTwoDecimals() {
        assertEquals("24.00", Money.format(new BigDecimal("24")));
        assertEquals("24.90", Money.format(new BigDecimal("24.9")));
    }

    @Test
    void formatNeverUsesScientificNotation() {
        assertEquals("100.00", Money.format(new BigDecimal("1E+2")));
    }
}
