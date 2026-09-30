package com.expensetracker.service;

import com.expensetracker.domain.Budget;
import com.expensetracker.domain.Category;
import com.expensetracker.store.BudgetStore;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the UI may do with budgets. Stateless, like {@link ExpenseService}.
 *
 * <p>There is no {@code deleteBudget}: nothing asks for one, and a method nothing calls is
 * a method that will be wrong when something finally does.
 */
public final class BudgetService {

    private final BudgetStore store;

    public BudgetService(BudgetStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public List<Budget> findAll() {
        return store.findAll();
    }

    /** Empty when no budget is set - the {@code NO_BUDGET} case, not an error. */
    public Optional<Budget> findByCategory(Category category) {
        return store.findByCategory(category);
    }

    /**
     * Named for what the user does, over a store method named for what the database does.
     * {@code Budget.of} validates and stamps {@code updatedAt} before anything is stored.
     *
     * @throws com.expensetracker.domain.ValidationException if the limit is not positive
     */
    public Budget setLimit(Category category, BigDecimal monthlyLimit) {
        Budget budget = Budget.of(category, monthlyLimit);
        store.upsert(budget);
        return budget;
    }
}
