package com.expensetracker.store;

import com.expensetracker.domain.CategorySpend;
import com.expensetracker.domain.Expense;
import com.expensetracker.domain.ExpenseFilter;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * Everything the rest of the application may ask of expense storage, on one screen.
 * No SQL and no {@code java.sql}: the service layer depends on this, not on the JDBC
 * implementation, so swapping the database means a new implementation and nothing above
 * it changes.
 *
 * <p>It lists only what is implemented, so it never claims storage can do something it
 * cannot.
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

    /**
     * Inserts every expense in one transaction: all of them, or none.
     *
     * <p>One method rather than a loop over {@link #add}, because each {@code add} commits
     * on its own - atomicity across many writes has to be an operation the store offers.
     *
     * @param onProgress called with the running count after each row
     * @param cancelled  polled before each row; true aborts and rolls back. Polled rather
     *                   than interrupted, because JDBC calls do not respond to interruption
     * @return the number inserted, or 0 if cancelled - never a partial count, because a
     *         partial import cannot happen
     * @throws StoreException if any row fails; nothing is inserted
     */
    int addAll(List<Expense> expenses, IntConsumer onProgress, BooleanSupplier cancelled);

    /** {@link #addAll(List, IntConsumer, BooleanSupplier)} with no progress and no cancel. */
    default int addAll(List<Expense> expenses) {
        return addAll(expenses, i -> { }, () -> false);
    }

    /**
     * One row per category with spending in the range, both dates inclusive; largest
     * total first, equal totals by category name. Never null; empty when nothing was spent.
     */
    List<CategorySpend> totalsByCategory(LocalDate from, LocalDate to);

    /**
     * The {@code limit} largest expenses in the range, largest first. Never null.
     *
     * @throws IllegalArgumentException if {@code limit} is not positive
     */
    List<Expense> findTop(LocalDate from, LocalDate to, int limit);
}
