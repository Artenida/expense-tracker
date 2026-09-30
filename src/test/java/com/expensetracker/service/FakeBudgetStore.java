package com.expensetracker.service;

import com.expensetracker.domain.Budget;
import com.expensetracker.domain.Category;
import com.expensetracker.store.BudgetStore;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory {@link BudgetStore}. An {@code EnumMap} keyed by category gives the upsert
 * behaviour - one budget per category - and enum order for {@code findAll}.
 *
 * <p>One known difference from the real store: {@code EnumMap} iterates in the enum's
 * declaration order, while the SQL orders alphabetically by name. No service test depends
 * on the order, which is why the difference is tolerated - and written down.
 */
final class FakeBudgetStore implements BudgetStore {

    private final Map<Category, Budget> rows = new EnumMap<>(Category.class);

    @Override
    public void upsert(Budget budget) {
        rows.put(budget.category(), budget);
    }

    @Override
    public List<Budget> findAll() {
        return List.copyOf(rows.values());
    }

    @Override
    public Optional<Budget> findByCategory(Category category) {
        return Optional.ofNullable(rows.get(category));
    }

    boolean isEmpty() {
        return rows.isEmpty();
    }
}
