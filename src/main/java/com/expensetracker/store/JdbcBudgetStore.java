package com.expensetracker.store;

import com.expensetracker.domain.Budget;
import com.expensetracker.domain.Category;
import com.expensetracker.domain.Money;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** {@link BudgetStore} over SQLite. Same patterns as {@link JdbcExpenseStore}. */
public final class JdbcBudgetStore implements BudgetStore {

    private final Database db;

    public JdbcBudgetStore(Database db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    /**
     * One statement, so there is no gap between "is there a row?" and "insert or
     * update" for another writer to slip into. The row count is ignored: by construction
     * it is always exactly one.
     */
    @Override
    public void upsert(Budget b) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(Sql.UPSERT_BUDGET)) {

            ps.setString(1, b.category().name());
            ps.setLong(2, b.limitCents());
            ps.setString(3, b.updatedAt().toString());
            ps.executeUpdate();

        } catch (SQLException ex) {
            throw new StoreException("failed to save budget for " + b.category(), ex);
        }
    }

    @Override
    public List<Budget> findAll() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(Sql.SELECT_ALL_BUDGETS);
             ResultSet rs = ps.executeQuery()) {

            List<Budget> found = new ArrayList<>();
            while (rs.next()) {
                found.add(map(rs));
            }
            return List.copyOf(found);

        } catch (SQLException ex) {
            throw new StoreException("failed to load budgets", ex);
        }
    }

    @Override
    public Optional<Budget> findByCategory(Category category) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(Sql.SELECT_BUDGET_BY_CATEGORY)) {

            ps.setString(1, category.name());

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }

        } catch (SQLException ex) {
            throw new StoreException("failed to load budget for " + category, ex);
        }
    }

    /** One row to one {@code Budget}; {@code restore} re-validates a hand-edited row. */
    private Budget map(ResultSet rs) throws SQLException {
        return Budget.restore(
                Category.parse(rs.getString("category")),
                Money.fromCents(rs.getLong("limit_cents")),
                Instant.parse(rs.getString("updated_at")));
    }
}
