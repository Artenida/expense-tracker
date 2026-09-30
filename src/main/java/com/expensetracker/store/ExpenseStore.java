package com.expensetracker.store;

import com.expensetracker.domain.Expense;
import com.expensetracker.domain.ExpenseFilter;

import java.util.List;
import java.util.Optional;

/**
 * Everything the rest of the application may ask of expense storage, on one screen.
 * No SQL and no {@code java.sql}: the service layer depends on this, not on the JDBC
 * implementation, so swapping the database means a new implementation and nothing above
 * it changes.
 *
 * <p>Grows again in sprint 09. It lists only what is implemented, so it never claims
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

    /**
     * Expenses matching the filter, newest first; same-day expenses in a stable order.
     * Never null; empty when none match. The returned list cannot be modified.
     */
    List<Expense> find(ExpenseFilter filter);

    /**
     * Replaces the amount, category, description and date of the stored expense with
     * this id. The id and {@code createdAt} are never changed.
     *
     * @return false if no row with that id exists. Reports the fact; whether that is an
     *         error is the caller's decision.
     */
    boolean update(Expense expense);

    /**
     * @return false if no row with that id exists - never deleted, or already deleted.
     *         The two cannot be told apart.
     */
    boolean delete(String id);
}
