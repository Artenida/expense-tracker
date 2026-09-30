package com.expensetracker.store;

import com.expensetracker.domain.Expense;

import java.util.Optional;

/**
 * Everything the rest of the application may ask of expense storage, on one screen.
 * No SQL and no {@code java.sql}: the service layer depends on this, not on the JDBC
 * implementation, so swapping the database means a new implementation and nothing above
 * it changes.
 *
 * <p>Grows in sprints 08 and 09. It lists only what is implemented, so it never claims
 * storage can do something it cannot.
 *
 * <p>Every method throws {@link StoreException} when the database itself fails.
 */
public interface ExpenseStore {

    /**
     * Inserts a new expense.
     *
     * @throws StoreException if an expense with the same id already exists
     */
    void add(Expense expense);

    /**
     * The expense with this id, or empty if there is none. "Not found" is a normal
     * outcome of a lookup, not an error.
     *
     * @throws IllegalArgumentException    if the stored row has an unknown category
     * @throws com.expensetracker.domain.ValidationException if the stored row breaks a domain rule
     */
    Optional<Expense> findById(String id);
}
