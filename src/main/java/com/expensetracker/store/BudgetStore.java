package com.expensetracker.store;

import com.expensetracker.domain.Budget;
import com.expensetracker.domain.Category;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Everything the rest of the application may ask of budget storage. At most one budget
 * per category: the category is the key.
 *
 * <p>Every method throws {@link StoreException} when the database itself fails.
 */
public interface BudgetStore {

    /** Inserts the budget for this category, or replaces the one already there. */
    void upsert(Budget budget);

    /**
     * Every configured budget, ordered alphabetically by category name. Never null;
     * empty when none are set. The returned list cannot be modified.
     */
    List<Budget> findAll();

    /** The budget for this category, or empty if none is set - the NO_BUDGET case. */
    Optional<Budget> findByCategory(Category category);

    /**
     * Every configured limit in cents, keyed by category. Never null.
     *
     * <p>A {@code default} method: written once, in terms of {@link #findAll()}, and
     * inherited by every implementation. Sprint 11 calls it once per summary instead of
     * querying category by category.
     */
    default Map<Category, Long> limitsByCategory() {
        return findAll().stream()
                .collect(Collectors.toUnmodifiableMap(Budget::category, Budget::limitCents));
    }
}
