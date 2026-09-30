package com.expensetracker.store;

/**
 * Every SQL statement in the project, as a named constant. No SQL string appears
 * anywhere else.
 *
 * <p>Package-private: nothing outside {@code store} has any business seeing these. Text
 * blocks, so a query can be pasted straight into the {@code sqlite3} CLI. Explicit
 * column lists, never {@code SELECT *}, so the mapper depends on something written down
 * rather than on the table's current shape.
 */
final class Sql {

    private Sql() {
    }

    static final String INSERT_EXPENSE = """
            INSERT INTO expenses (id, amount_cents, category, description, spent_on, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    static final String SELECT_EXPENSE_BY_ID = """
            SELECT id, amount_cents, category, description, spent_on, created_at
            FROM expenses
            WHERE id = ?
            """;

    /**
     * One statement for both "one category" and "All": bind the category to parameters
     * 3 and 4, or NULL to both. The ORDER BY is a total order - ties on the same day are
     * the normal case, and without the tiebreakers they could come back in any order.
     */
    static final String SELECT_EXPENSES_FILTERED = """
            SELECT id, amount_cents, category, description, spent_on, created_at
            FROM expenses
            WHERE spent_on BETWEEN ? AND ?
              AND (? IS NULL OR category = ?)
            ORDER BY spent_on DESC, created_at DESC, id DESC
            """;

    /** Leaves id and created_at alone: identity and history cannot change by accident. */
    static final String UPDATE_EXPENSE = """
            UPDATE expenses
            SET amount_cents = ?, category = ?, description = ?, spent_on = ?
            WHERE id = ?
            """;

    static final String DELETE_EXPENSE = "DELETE FROM expenses WHERE id = ?";

    /**
     * Insert, or update if the category already has a row - atomic because it is one
     * statement. {@code excluded} is the row that would have been inserted.
     */
    static final String UPSERT_BUDGET = """
            INSERT INTO budgets (category, limit_cents, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(category) DO UPDATE
              SET limit_cents = excluded.limit_cents,
                  updated_at  = excluded.updated_at
            """;

    /** Alphabetical by the stored name, not in the enum's declaration order. */
    static final String SELECT_ALL_BUDGETS = """
            SELECT category, limit_cents, updated_at FROM budgets ORDER BY category
            """;

    static final String SELECT_BUDGET_BY_CATEGORY = """
            SELECT category, limit_cents, updated_at FROM budgets WHERE category = ?
            """;

    /**
     * The second ORDER BY key is sprint 05's requirement: equal totals come back in a
     * defined order, or the SQL and stream summaries disagree intermittently.
     */
    static final String SELECT_TOTALS_BY_CATEGORY = """
            SELECT category, SUM(amount_cents) AS total, COUNT(*) AS entries
            FROM expenses
            WHERE spent_on BETWEEN ? AND ?
            GROUP BY category
            ORDER BY total DESC, category ASC
            """;

    /** LIMIT can be bound: it is a value. ORDER BY ? could not be - that is structure. */
    static final String SELECT_TOP_EXPENSES = """
            SELECT id, amount_cents, category, description, spent_on, created_at
            FROM expenses
            WHERE spent_on BETWEEN ? AND ?
            ORDER BY amount_cents DESC, spent_on DESC, id DESC
            LIMIT ?
            """;
}
