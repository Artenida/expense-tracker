# Sprint 08 · Build specification

---

## New constants in `store/Sql.java`

```java
static final String SELECT_EXPENSES_FILTERED = """
    SELECT id, amount_cents, category, description, spent_on, created_at
    FROM expenses
    WHERE spent_on BETWEEN ? AND ?
      AND (? IS NULL OR category = ?)
    ORDER BY spent_on DESC, created_at DESC, id DESC
    """;

static final String UPDATE_EXPENSE = """
    UPDATE expenses
    SET amount_cents = ?, category = ?, description = ?, spent_on = ?
    WHERE id = ?
    """;

static final String DELETE_EXPENSE = "DELETE FROM expenses WHERE id = ?";

static final String UPSERT_BUDGET = """
    INSERT INTO budgets (category, limit_cents, updated_at)
    VALUES (?, ?, ?)
    ON CONFLICT(category) DO UPDATE
      SET limit_cents = excluded.limit_cents,
          updated_at  = excluded.updated_at
    """;

static final String SELECT_ALL_BUDGETS = """
    SELECT category, limit_cents, updated_at FROM budgets ORDER BY category
    """;

static final String SELECT_BUDGET_BY_CATEGORY = """
    SELECT category, limit_cents, updated_at FROM budgets WHERE category = ?
    """;
```

`UPDATE_EXPENSE` deliberately does **not** set `id` or `created_at`. They are identity
and history; the edit dialog cannot change them, and leaving them out of the statement
means it cannot happen by accident either.

---

## `ExpenseStore` — the interface grows

```java
/** Expenses matching the filter, newest first. Never null; empty when none match. */
List<Expense> find(ExpenseFilter filter);

/** Returns false if no row with that id exists. */
boolean update(Expense expense);

/** Returns false if no row with that id exists. */
boolean delete(String id);
```

"Never null; empty when none match" is the no-`null` rule written down where callers
read it. Every collection-returning method in the project gets this line.

---

## `JdbcExpenseStore.find`

```java
@Override
public List<Expense> find(ExpenseFilter filter) {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(Sql.SELECT_EXPENSES_FILTERED)) {

        ps.setString(1, filter.from().toString());
        ps.setString(2, filter.to().toString());

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
            while (rs.next()) found.add(map(rs));
            return List.copyOf(found);
        }

    } catch (SQLException ex) {
        throw new StoreException("failed to find expenses for " + filter.month(), ex);
    }
}
```

`List.copyOf` returns an immutable list. The caller cannot modify what the store handed
back, which matters from sprint 14 where the same list feeds an `ObservableList`.

`filter.from()` and `filter.to()` come from sprint 05, so the month-to-dates arithmetic
is not repeated here. Notice that this method has no idea what a month is.

### `update` and `delete`

```java
@Override
public boolean update(Expense e) {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(Sql.UPDATE_EXPENSE)) {

        ps.setLong  (1, Money.toCents(e.amount()));
        ps.setString(2, e.category().name());
        ps.setString(3, e.description());
        ps.setString(4, e.date().toString());
        ps.setString(5, e.id());

        return ps.executeUpdate() > 0;

    } catch (SQLException ex) {
        throw new StoreException("failed to update expense " + e.id(), ex);
    }
}
```

Watch the parameter numbering: the `WHERE` clause's `?` is **last**, because parameters
are numbered by position in the statement text, not by importance. Off-by-one here is
the most common JDBC bug there is, and it does not fail loudly — it updates the wrong
column with the wrong value.

`delete` is the same shape with one parameter.

---

## `store/BudgetStore.java`

```java
public interface BudgetStore {

    /** Inserts or replaces the budget for this category. */
    void upsert(Budget budget);

    /** Every configured budget, ordered by category. Never null. */
    List<Budget> findAll();

    /** The budget for this category, or empty if none is set. */
    Optional<Budget> findByCategory(Category category);
}
```

`findByCategory` returning `Optional` is the specification's own example of where
`Optional` belongs: *"a budget that may not be set"*. It feeds sprint 02's
`BudgetStatus.of(..., OptionalLong)` and the `NO_BUDGET` case.

## `JdbcBudgetStore`

Same patterns as the expense store, with its own mapper:

```java
private Budget map(ResultSet rs) throws SQLException {
    return Budget.restore(
        Category.parse(rs.getString("category")),
        Money.fromCents(rs.getLong("limit_cents")),
        Instant.parse(rs.getString("updated_at")));
}
```

`upsert` binds three parameters and ignores the return of `executeUpdate` — by
construction the statement always affects exactly one row.

### A convenience the summary will want

```java
/** Every configured limit, keyed by category. Never null. */
default Map<Category, Long> limitsByCategory() {
    return findAll().stream()
        .collect(Collectors.toMap(Budget::category, Budget::limitCents));
}
```

A `default` method on the interface: implemented once, inherited by every implementer,
and expressed purely in terms of other interface methods. A Postgres implementation gets
it for free.

Sprint 11 calls this once per summary rather than querying per category — seven queries
replaced by one, and more importantly one *place* where "what limits are set" is
answered.
