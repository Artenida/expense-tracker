package com.expensetracker.service;

/**
 * The service's policy on the store's fact: the store says "no row had that id", and for
 * this application that is an error worth telling the user about - typically because
 * another window or process deleted the expense first.
 *
 * <p>Unchecked, like {@code StoreException} and {@code ValidationException}, so nothing
 * between the thrower and sprint 18's handler needs a {@code throws} clause. The id is a
 * field as well as part of the message, so a caller can act on it - refresh that row -
 * without parsing it back out of a string.
 */
public class ExpenseNotFoundException extends RuntimeException {

    private final String id;

    public ExpenseNotFoundException(String id) {
        super("no expense with id " + id);
        this.id = id;
    }

    public String id() {
        return id;
    }
}
