package com.expensetracker.store;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.CategorySpend;
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
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

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

            bind(ps, e);

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

    /** The six insert parameters, shared by {@code add} and {@code addAll} so they cannot drift. */
    private static void bind(PreparedStatement ps, Expense e) throws SQLException {
        ps.setString(1, e.id());
        // The single conversion point from sprint 03 - never setBigDecimal or a double.
        ps.setLong(2, Money.toCents(e.amount()));
        // name(), not displayName(): "GROCERIES" survives a translated UI.
        ps.setString(3, e.category().name());
        ps.setString(4, e.description());
        // ISO-8601 by definition, so text order is date order.
        ps.setString(5, e.date().toString());
        ps.setString(6, e.createdAt().toString());
    }

    /**
     * Rows are queued and sent 500 at a time: one round trip per batch instead of per
     * row, without holding a 50 000-row queue in memory. The batching sits inside the
     * transaction, so it does not affect atomicity.
     */
    private static final int BATCH_SIZE = 500;

    /**
     * The one operation that owns a transaction across many statements.
     *
     * <p>Two differences from SPEC.md's version, both about the same danger. This driver
     * <em>commits</em> an open transaction when {@code setAutoCommit(true)} is called, so
     * restoring autocommit is only safe after a commit or a rollback. SPEC.md catches only
     * {@code SQLException}: a {@code RuntimeException} from the progress callback would skip
     * the rollback, and its {@code finally} would then commit every batch already sent. Here
     * any exception rolls back first. And the connection stays in try-with-resources - the
     * inner catch runs, and rolls back, before the outer block closes it.
     */
    @Override
    public int addAll(List<Expense> expenses, IntConsumer onProgress, BooleanSupplier cancelled) {
        if (expenses.isEmpty()) {
            return 0;
        }

        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(Sql.INSERT_EXPENSE)) {
                int inserted = 0;
                for (Expense e : expenses) {
                    // Checked before each bind, so () -> true inserts nothing at all.
                    if (cancelled.getAsBoolean()) {
                        c.rollback();
                        return 0;
                    }

                    bind(ps, e);
                    ps.addBatch();
                    inserted++;

                    if (inserted % BATCH_SIZE == 0) {
                        ps.executeBatch();
                    }
                    onProgress.accept(inserted);
                }
                // Whatever is left in the queue - 200 rows of a 1 200-row import.
                ps.executeBatch();

                c.commit();
                return inserted;

            } catch (SQLException | RuntimeException ex) {
                Jdbc.rollbackQuietly(c, ex);
                if (ex instanceof SQLException sql) {
                    throw new StoreException("failed to import " + expenses.size() + " expenses", sql);
                }
                throw (RuntimeException) ex;
            } finally {
                // Safe only now: the transaction has been committed or rolled back. A pooled
                // connection handed back with autocommit off would silently never commit.
                restoreAutoCommit(c);
            }
        } catch (SQLException ex) {
            // Only open's pragmas, setAutoCommit(false) or close() can land here.
            throw new StoreException("failed to import " + expenses.size() + " expenses", ex);
        }
    }

    /** Ignored on failure: the connection is closed immediately afterwards anyway. */
    private static void restoreAutoCommit(Connection c) {
        try {
            c.setAutoCommit(true);
        } catch (SQLException ignored) {
            // Nothing useful to do - and throwing here would hide the exception in flight.
        }
    }

    /** The database does the grouping: seven rows cross the boundary, not four thousand. */
    @Override
    public List<CategorySpend> totalsByCategory(LocalDate from, LocalDate to) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(Sql.SELECT_TOTALS_BY_CATEGORY)) {

            ps.setString(1, from.toString());
            ps.setString(2, to.toString());

            // With GROUP BY, an empty range gives no groups - no rows - rather than the
            // single row of NULL that SUM without GROUP BY would return.
            try (ResultSet rs = ps.executeQuery()) {
                List<CategorySpend> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new CategorySpend(
                            Category.parse(rs.getString("category")),
                            rs.getLong("total"),
                            rs.getInt("entries")));
                }
                return List.copyOf(out);
            }

        } catch (SQLException ex) {
            throw new StoreException("failed to total expenses from " + from + " to " + to, ex);
        }
    }

    /** Sorting and limiting in SQL: only {@code limit} rows are ever read out and mapped. */
    @Override
    public List<Expense> findTop(LocalDate from, LocalDate to, int limit) {
        // LIMIT 0 returns nothing and LIMIT -1 means "no limit" in SQLite; neither is
        // what a caller asking for the top -1 expenses meant.
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }

        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(Sql.SELECT_TOP_EXPENSES)) {

            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            ps.setInt(3, limit);

            try (ResultSet rs = ps.executeQuery()) {
                List<Expense> found = new ArrayList<>();
                while (rs.next()) {
                    found.add(map(rs));
                }
                return List.copyOf(found);
            }

        } catch (SQLException ex) {
            throw new StoreException("failed to find top expenses from " + from + " to " + to, ex);
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
