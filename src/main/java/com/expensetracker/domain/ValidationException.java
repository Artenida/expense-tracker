package com.expensetracker.domain;

import java.util.List;

/**
 * Unchecked, so no constructor or service method needs a {@code throws} clause for a
 * condition that means "a caller passed something Validation would have rejected".
 */
public class ValidationException extends RuntimeException {

    private final List<String> errors;

    public ValidationException(List<String> errors) {
        // A readable getMessage() for logs and stack traces; errors() keeps the
        // structured list for the dialog to render field by field.
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
