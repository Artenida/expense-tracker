# Sprint 07 · The first store

**Time:** ~2.5 hours · **Prerequisites:** 06 · **Produces:** `ExpenseStore`, `JdbcExpenseStore` (add + findById), `Sql`

---

## Purpose

Two operations — write one expense, read it back — and with them the three patterns
that every remaining query in the project reuses: a `PreparedStatement` with bound
parameters, a `ResultSet` turned into a domain object by one shared method, and an
interface that hides which database is underneath.

This is also where spec test 5 becomes possible: *data written in one session is present
in a new session against the same file.* Until now nothing has survived the end of a
method.

## New concepts

### 1. An interface is a seam

```java
public interface ExpenseStore {
    void add(Expense expense);
    Optional<Expense> findById(String id);
}
```

No SQL, no `java.sql`, no mention of SQLite. The service layer in sprint 10 will depend
on *this*, not on `JdbcExpenseStore`. That gives you two things:

- **Substitution.** The original specification's optional milestone swaps SQLite for
  Postgres. If the layering is right that is a new class implementing this interface and
  a different connection string — nothing above it changes. The interface is what makes
  "nothing above it changes" true rather than hopeful.
- **A place to read the contract.** Open `ExpenseStore.java` and you can see everything
  the rest of the application is allowed to ask of storage, on one screen, with no
  implementation noise. That is a genuinely useful thing to be able to do to an
  unfamiliar codebase.

The interface returns `Optional<Expense>`, not `Expense`. "Not found" is a normal
outcome of a lookup, not an error, and `Optional` is how the type says so.

### 2. `PreparedStatement` — and why concatenation is not an option

```java
// never, under any circumstances
String sql = "SELECT * FROM expenses WHERE id = '" + id + "'";

// always
PreparedStatement ps = c.prepareStatement("SELECT ... WHERE id = ?");
ps.setString(1, id);
```

The `?` is a **placeholder**, not string interpolation. The database parses the SQL
first, with the placeholders in it, and the value is supplied afterwards as *data*. A
value containing `'; DROP TABLE expenses; --` cannot change the meaning of the statement
because the statement was already compiled before the value arrived.

Two things that catch people out:

- **Parameters are 1-indexed.** `setString(1, ...)` is the first `?`. Everything else in
  Java is 0-indexed; this is not.
- **You cannot parameterise identifiers.** `ORDER BY ?` does not work — only values can
  be bound, never table names, column names or keywords. That constraint is a feature:
  it means user input can never become SQL structure.

### 3. `ResultSet` is a cursor, not a collection

```java
try (ResultSet rs = ps.executeQuery()) {
    while (rs.next()) { ... }
}
```

A `ResultSet` starts positioned *before* the first row. `next()` advances and returns
`false` when there are no more. It is a live cursor over the database's results — which
is why it must be closed, and why you cannot return one from the store. Hand a
`ResultSet` upwards and the caller receives something that stops working the moment the
connection closes.

Read columns **by name**, not by index. `rs.getLong("amount_cents")` still works after
sprint 06's `ALTER TABLE` changed the column count; `rs.getLong(2)` is a bet that the
column order never changes.

### 4. One mapper, used by every query

```java
private Expense map(ResultSet rs) throws SQLException { ... }
```

One private method converts a row into an `Expense`. Every query in sprints 07, 08 and
09 calls it. When sprint 20 adds a column, there is one method to change.

Note it declares `throws SQLException`. It is private and only called from inside a
`try` that already catches `SQLException`, so wrapping inside the mapper would just be
noise.

## The conversions the mapper performs

Each one is a place the specification warns about, and all four happen in this method:

| Column | Type in SQLite | Conversion |
|---|---|---|
| `amount_cents` | `INTEGER` | `Money.fromCents(rs.getLong(...))` — never `getDouble` |
| `spent_on` | `TEXT` | `LocalDate.parse(...)` — SQLite has no date type |
| `created_at` | `TEXT` | `Instant.parse(...)` |
| `category` | `TEXT` | `Category.parse(...)` — sprint 02, which throws a readable message |

`Category.parse` is the interesting one. A value that is not a valid category means
somebody edited the file by hand, and the parse throwing here — at the boundary, naming
the bad value — is much better than a `null` category travelling three layers up.

## What you build

- `Sql` — the SQL constants
- `ExpenseStore` — the interface
- `JdbcExpenseStore` — `add`, `findById`, `map`

## Definition of done

- [ ] Spec test 5: an expense added through one store instance is found by a second
      instance opened against the same file
- [ ] Spec test 4: a description of `'; DROP TABLE expenses; --` is stored as text and
      the table survives
- [ ] `findById` on a missing id returns `Optional.empty()`, not `null`, not a throw
- [ ] No `SQLException` escapes the `store` package
- [ ] The `onlyStoreImportsJavaSql` guard test passes — and would now fail if you broke it

---

## Reading check

1. `ps.setString(1, id)` — why `1` and not `0`?
2. `findById` returns `Optional<Expense>`. What would the signature have to be if it
   returned `Expense`, and what would every caller then have to remember?
3. The mapper reads columns by name. Give a concrete change to the schema that would
   break an index-based mapper and leave a name-based one working.
4. You return a `List<Expense>` from a query and the connection closes when the method
   returns. Why do the `Expense` objects still work, when a `ResultSet` would not?
