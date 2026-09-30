package com.expensetracker.service;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.Expense;
import com.expensetracker.domain.ExpenseFilter;
import com.expensetracker.store.ExpenseStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the UI may do with expenses, in one place. Half the methods are one-line
 * pass-throughs today; they exist so reads and writes both go through the same door, and
 * the others hold policy that would otherwise end up in a button handler.
 *
 * <p>Stateless on purpose: no cache, every call goes to the store. From sprint 15 a
 * background import writes while the table holds rows it read earlier, and a cache
 * would drift out of step with the file.
 *
 * <p>No validation here. {@code Expense} validates itself; a second copy of the rule in
 * this class would be a second thing to keep in step.
 */
public final class ExpenseService {

    private final ExpenseStore store;

    /** The interface, not the JDBC class: a test can hand in a fake with no database. */
    public ExpenseService(ExpenseStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public List<Expense> find(ExpenseFilter filter) {
        return store.find(filter);
    }

    public Optional<Expense> findById(String id) {
        return store.findById(id);
    }

    /**
     * Takes the parts rather than an {@code Expense}, so the dialog never constructs a
     * domain object, an id or a timestamp. If validation fails, {@code Expense.create}
     * throws before the store is reached - nothing is written, by ordering rather than
     * by a check.
     *
     * @return the created expense, so the caller has its id without a second lookup
     * @throws com.expensetracker.domain.ValidationException if any part is invalid
     */
    public Expense add(BigDecimal amount, Category category, String description, LocalDate date) {
        Expense expense = Expense.create(amount, category, description, date);
        store.add(expense);
        return expense;
    }

    /**
     * Takes a whole {@code Expense}, unlike {@code add}: the caller already has one, from
     * {@code selected.edit(...)}, which keeps the id and {@code createdAt}.
     *
     * @throws ExpenseNotFoundException if no stored expense has this id
     */
    public Expense update(Expense edited) {
        if (!store.update(edited)) {
            throw new ExpenseNotFoundException(edited.id());
        }
        return edited;
    }

    /**
     * The store reports a fact - no row had that id. This applies the policy: deleting
     * something that is not there is an error the user should hear about.
     *
     * @throws ExpenseNotFoundException if no stored expense has this id
     */
    public void delete(String id) {
        if (!store.delete(id)) {
            throw new ExpenseNotFoundException(id);
        }
    }

    /**
     * Takes the month the user picked; {@code ExpenseFilter} already knows how to turn a
     * month into its first and last day, so this is not a second place that does.
     */
    public List<Expense> topExpenses(YearMonth month, int limit) {
        ExpenseFilter filter = ExpenseFilter.of(month);
        return store.findTop(filter.from(), filter.to(), limit);
    }
}
