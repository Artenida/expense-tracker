package com.expensetracker.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationExceptionTest {

    @Test
    void isUncheckedSoNoSignatureNeedsAThrowsClause() {
        assertTrue(RuntimeException.class.isAssignableFrom(ValidationException.class));
    }

    @Test
    void messageJoinsTheErrorsForLogsAndStackTraces() {
        ValidationException e = new ValidationException(
                List.of("amount is required", "date cannot be in the future"));

        assertEquals("amount is required; date cannot be in the future", e.getMessage());
    }

    @Test
    void errorsKeepTheStructuredListForTheDialog() {
        List<String> errors = List.of("amount must be greater than zero");

        assertEquals(errors, new ValidationException(errors).errors());
    }

    @Test
    void mutatingTheCallersListCannotChangeWhatTheCatchBlockSees() {
        List<String> mutable = new ArrayList<>(List.of("amount is required"));
        ValidationException e = new ValidationException(mutable);

        mutable.add("sneaked in after the throw");

        assertEquals(List.of("amount is required"), e.errors());
        assertThrows(UnsupportedOperationException.class, () -> e.errors().add("nor this"));
    }
}
