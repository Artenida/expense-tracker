package com.expensetracker.store;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.Expense;
import com.expensetracker.domain.ExpenseFilter;
import com.expensetracker.domain.Money;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ExpenseStore} over SQLite. Every value reaches the database through a bound
 * {@code ?} parameter, never through string concatenation, so no value can change what
 * a statement means.
 */
public final class JdbcExpenseStore implements ExpenseStore {

    private final Database db;

    /**
     * The database comes in rather than being built here, so a test can point the store
     * at a temporary file and sprint 13's App can decide the real one.
     */
    public JdbcExpenseStore(Database db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    @Override
    public void add(Expense e) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(Sql.INSERT_EXPENSE)) {

            ps.setString(1, e.id());
            // The single conversion point from sprint 03 - never setBigDecimal or a double.
            ps.setLong(2, Money.toCents(e.amount()));
            // name(), not displayName(): "GROCERIES" survives a translated UI.
            ps.setString(3, e.category().name());
            ps.setString(4, e.description());
            // ISO-8601 by definition, so text order is date order.
            ps.setString(5, e.date().toString());
            ps.setString(6, e.createdAt().toString());

            // The row count is ignored: a failed insert throws rather than returning 0.
            ps.executeUpdate();

        } catch (SQLException ex) {
            throw new StoreException("failed to add expense " + e.id(), ex);
        }
    }

    @Override
    public Optional<Expense> findById(String id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(Sql.SELECT_EXPENSE_BY_ID)) {

            ps.setString(1, id);

            // Its own block, so the result set is visibly alive only while it is read.
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }

        } catch (SQLException ex) {
            throw new StoreException("failed to load expense " + id, ex);
        }
    }

    /** Knows nothing about months: the filter already turned one into two dates. */
    @Override
    public List<Expense> find(ExpenseFilter filter) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(Sql.SELECT_EXPENSES_FILTERED)) {

            ps.setString(1, filter.from().toString());
            ps.setString(2, filter.to().toString());

            // Placeholders are positional - there is no "the same one again" - so the
            // category is bound twice. The one place a parameter is deliberately NULL.
            if (filter.category().isPresent()) {
                String name = filter.category().get().name();
                ps.setString(3, name);
                ps.setString(4, name);
            } else {
                ps.setNull(3, Types.VARCHAR);
                ps.setNull(4, Types.VARCHAR);
            }

            try (ResultSet rs = ps.executeQuery()) {
                List<Expense> found = new ArrayList<>();
                while (rs.next()) {
                    found.add(map(rs));
                }
                return List.copyOf(found);
            }

        } catch (SQLException ex) {
            throw new StoreException("failed to find expenses for " + filter.month(), ex);
        }
    }

    @Override
    public boolean update(Expense e) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(Sql.UPDATE_EXPENSE)) {

            // Numbered by position in the text, so the WHERE clause's id is last.
            ps.setLong(1, Money.toCents(e.amount()));
            ps.setString(2, e.category().name());
            ps.setString(3, e.description());
            ps.setString(4, e.date().toString());
            ps.setString(5, e.id());

            // The row count is the only signal that the targeted row was not there.
            return ps.executeUpdate() > 0;

        } catch (SQLException ex) {
            throw new StoreException("failed to update expense " + e.id(), ex);
        }
    }

    @Override
    public boolean delete(String id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(Sql.DELETE_EXPENSE)) {

            ps.setString(1, id);
            return ps.executeUpdate() > 0;

        } catch (SQLException ex) {
            throw new StoreException("failed to delete expense " + id, ex);
        }
    }

    /**
     * One row to one {@code Expense}; every query uses it, so a new column is one change.
     * Columns are read by name, which survives a change in column order.
     *
     * <p>{@code Expense.restore} re-runs the domain validation and {@code Category.parse}
     * names a bad value, so a hand-edited row fails here, at the boundary, rather than
     * travelling three layers up as a wrong total.
     *
     * <p>Declares {@code SQLException} rather than wrapping it: it is only called inside
     * a try that already does.
     */
    private Expense map(ResultSet rs) throws SQLException {
        return Expense.restore(
                rs.getString("id"),
                Money.fromCents(rs.getLong("amount_cents")),
                Category.parse(rs.getString("category")),
                rs.getString("description"),
                LocalDate.parse(rs.getString("spent_on")),
                Instant.parse(rs.getString("created_at")));
    }
}
