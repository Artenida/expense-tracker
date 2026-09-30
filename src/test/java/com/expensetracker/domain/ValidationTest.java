package com.expensetracker.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationTest {

    // --- amount -------------------------------------------------------------

    /**
     * Spec test 14. Note "0" and "0.00": they pass the format check in Money.parse and
     * fail the business check here, which is why the two live in different classes.
     */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "", "   ", "1.234", "-2", "12,50", "0", "0.00"})
    void badAmountsProduceAMessage(String raw) {
        assertFalse(Validation.amount(raw).isEmpty(),
                () -> "'" + raw + "' should have produced a message");
    }

    @Test
    void validAmountProducesNoMessages() {
        assertTrue(Validation.amount("24.90").isEmpty());
    }

    @Test
    void nullAmountIsReported() {
        List<String> errors = Validation.amount(null);

        assertEquals(List.of("amount is required"), errors);
    }

    @Test
    void zeroIsRejectedAsABusinessRuleNotAFormatOne() {
        assertEquals(List.of("amount must be greater than zero"), Validation.amount("0.00"));
    }

    // --- description --------------------------------------------------------

    @Test
    void descriptionOf100CharsIsAccepted() {
        assertTrue(Validation.description("a".repeat(100)).isEmpty(),
                "the boundary is inclusive");
    }

    @Test
    void descriptionOf101CharsIsReported() {
        List<String> errors = Validation.description("a".repeat(101));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("101"),
                () -> "message should name the actual length, was: " + errors.get(0));
    }

    @Test
    void blankDescriptionIsReported() {
        assertFalse(Validation.description("   ").isEmpty());
        assertFalse(Validation.description("").isEmpty());
        assertFalse(Validation.description(null).isEmpty());
    }

    // --- date ---------------------------------------------------------------

    @Test
    void todayIsAccepted() {
        assertTrue(Validation.date(LocalDate.now()).isEmpty(),
                "\"not in the future\" does not mean \"not today\"");
    }

    /** Spec test 3, at the validator level. */
    @Test
    void tomorrowIsReported() {
        assertEquals(List.of("date cannot be in the future"),
                Validation.date(LocalDate.now().plusDays(1)));
    }

    @Test
    void nullDateIsReported() {
        assertEquals(List.of("date is required"), Validation.date(null));
    }

    @Test
    void pastDatesAreAccepted() {
        assertTrue(Validation.date(LocalDate.now().minusYears(5)).isEmpty());
    }
}
