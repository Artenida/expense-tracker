package com.expensetracker.service;

import com.expensetracker.domain.CategorySpend;
import com.expensetracker.domain.Expense;
import com.expensetracker.domain.ExpenseFilter;
import com.expensetracker.store.ExpenseStore;
import com.expensetracker.store.StoreException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * An in-memory {@link ExpenseStore} for testing the service with no database.
 *
 * <p>Only the row operations are real. The queries record what they were asked and return
 * what the test set up, because what the service tests check is <em>what the service
 * passes down</em> - the filtering, ordering and grouping are the JDBC store's job and
 * are tested there. Anything not needed throws, so a test can never pass by relying on
 * fake behaviour that the real store does not share.
 */
final class FakeExpenseStore implements ExpenseStore {

    private final Map<String, Expense> rows = new LinkedHashMap<>();

    ExpenseFilter lastFilter;
    LocalDate lastTopFrom;
    LocalDate lastTopTo;
    int lastTopLimit;
    List<Expense> queryResult = List.of();

    // --- the row operations: real ------------------------------------------

    @Override
    public void add(Expense e) {
        if (rows.putIfAbsent(e.id(), e) != null) {
            throw new StoreException("duplicate id " + e.id());
        }
    }

    @Override
    public Optional<Expense> findById(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public boolean update(Expense e) {
        return rows.replace(e.id(), e) != null;
    }

    @Override
    public boolean delete(String id) {
        return rows.remove(id) != null;
    }

    // --- the queries: recorded ---------------------------------------------

    @Override
    public List<Expense> find(ExpenseFilter filter) {
        lastFilter = filter;
        return queryResult;
    }

    @Override
    public List<Expense> findTop(LocalDate from, LocalDate to, int limit) {
        lastTopFrom = from;
        lastTopTo = to;
        lastTopLimit = limit;
        return queryResult;
    }

    // --- not needed by the service tests -----------------------------------

    @Override
    public int addAll(List<Expense> expenses, IntConsumer onProgress, BooleanSupplier cancelled) {
        throw new UnsupportedOperationException("not used by the service tests");
    }

    @Override
    public List<CategorySpend> totalsByCategory(LocalDate from, LocalDate to) {
        throw new UnsupportedOperationException("not used by the service tests");
    }

    // --- for the tests ------------------------------------------------------

    boolean isEmpty() {
        return rows.isEmpty();
    }

    List<Expense> rows() {
        return new ArrayList<>(rows.values());
    }

    /** Simulates another process deleting the row behind the service's back. */
    void removeDirectly(String id) {
        rows.remove(id);
    }
}
