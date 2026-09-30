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
}
