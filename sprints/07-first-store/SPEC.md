# Sprint 07 · Build specification

---

## `store/Sql.java`

Every statement as a named constant. No SQL string appears anywhere else in the project.

```java
package com.expensetracker.store;

final class Sql {
    private Sql() {}

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
```

Three deliberate choices:

- **Package-private** (`final class Sql`, not `public`). Nothing outside `store` has any
  business seeing these, and the compiler can enforce that.
- **Text blocks** (`"""`). Java 15+. The SQL keeps its line breaks and indentation, so
  you can paste it straight into the `sqlite3` CLI when a query misbehaves.
- **Explicit column lists, never `SELECT *`.** Sprint 06's V2 added a `note` column the
  application does not use. `SELECT *` would fetch it on every query for no reason, and
  more importantly it makes the mapper's contract depend on the table's shape rather
  than on something written down.

---

## `store/ExpenseStore.java`

```java
package com.expensetracker.store;

public interface ExpenseStore {

    /** Inserts a new expense. Throws StoreException if the id already exists. */
    void add(Expense expense);

    /** The expense with this id, or empty if there is none. */
    Optional<Expense> findById(String id);
}
```

It grows in sprints 08 and 09. Keeping it to what is implemented means the interface
never lies about what storage can do.

Write the Javadoc. On an interface it is the only place the *contract* can live — the
implementation shows how, not what is promised.

---

## `store/JdbcExpenseStore.java`

```java
public final class JdbcExpenseStore implements ExpenseStore {

    private final Database db;

    public JdbcExpenseStore(Database db) { this.db = Objects.requireNonNull(db); }
}
```

The `Database` comes in through the constructor rather than being created here. That is
what lets a test point the store at a `@TempDir` file, and what lets sprint 13's `App`
decide the real location. A class that constructs its own dependencies cannot be tested
against a different one.

### `add`

```java
@Override
public void add(Expense e) {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(Sql.INSERT_EXPENSE)) {

        ps.setString(1, e.id());
        ps.setLong  (2, Money.toCents(e.amount()));
        ps.setString(3, e.category().name());
        ps.setString(4, e.description());
        ps.setString(5, e.date().toString());
        ps.setString(6, e.createdAt().toString());

        ps.executeUpdate();

    } catch (SQLException ex) {
        throw new StoreException("failed to add expense " + e.id(), ex);
    }
}
```

| Line | Why |
|---|---|
| `Money.toCents` | The single conversion point from sprint 03. `setBigDecimal` would let SQLite store it in a `REAL`-ish way and silently lose precision. |
| `category().name()` | The enum constant's name — `"GROCERIES"` — not `displayName()`. `Category.parse` reads it back. Storing the display name would break the moment you translate the UI. |
| `date().toString()` | `LocalDate.toString()` is ISO-8601 by definition: `2026-09-15`. Not a locale-dependent format, and it sorts correctly as text. |
| `createdAt().toString()` | Same for `Instant` — `2026-09-15T14:32:01.123Z`, always UTC. |

`executeUpdate()` returns the number of rows affected. `add` ignores it because a
failed insert throws rather than returning 0. Sprint 08's `update` and `delete` do check
it, and the difference is worth noticing.

### `findById`

```java
@Override
public Optional<Expense> findById(String id) {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(Sql.SELECT_EXPENSE_BY_ID)) {

        ps.setString(1, id);

        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
        }

    } catch (SQLException ex) {
        throw new StoreException("failed to load expense " + id, ex);
    }
}
```

The `ResultSet` gets its **own nested** try-with-resources. It could go in the outer
one — `executeQuery()` cannot be called until the statement exists, so the declarations
would have to be in that order anyway — but nesting makes the lifetime obvious: the
result set is only alive inside the block that reads it.

### `map`

```java
private Expense map(ResultSet rs) throws SQLException {
    return Expense.restore(
        rs.getString("id"),
        Money.fromCents(rs.getLong("amount_cents")),
        Category.parse(rs.getString("category")),
        rs.getString("description"),
        LocalDate.parse(rs.getString("spent_on")),
        Instant.parse(rs.getString("created_at")));
}
```

Every query from here on calls this. It is four lines and it is the most-executed code
in the project.

`Expense.restore` re-runs the domain validation from sprint 04. A row that violates the
rules — because someone edited the file — fails here, at the boundary, naming the
problem. That is deliberate: the alternative is an invalid `Expense` circulating through
three layers and producing a wrong total with no indication of where it came from.

---

## Wiring it up for tests

There is no `App` yet. Tests construct the whole stack by hand:

```java
@TempDir Path tempDir;

private JdbcExpenseStore store() {
    Database db = new Database(tempDir.resolve("test.db"));
    new SchemaMigrator(db).migrate();
    return new JdbcExpenseStore(db);
}
```

Three lines, and they are the same three lines `App` will run in sprint 13. Being able
to stand the storage layer up in a test with no framework is a property worth
protecting.
