# Sprint 09 · Build specification

---

## `domain/CategorySpend.java`

```java
public record CategorySpend(Category category, long spentCents, int entryCount) {
    public CategorySpend {
        Objects.requireNonNull(category);
        if (spentCents < 0)  throw new IllegalArgumentException("spentCents < 0");
        if (entryCount < 0)  throw new IllegalArgumentException("entryCount < 0");
    }
}
```

In `domain` rather than `store`, because the store must return domain objects — but it
carries **cents**, not `BigDecimal`, because it is an intermediate on the way to sprint
05's `CategoryTotal.of(category, spentCents, limitCents)`. Converting to `BigDecimal`
here and back to cents there would be two pointless conversions and one more chance to
get the scale wrong.

---

## New constants in `Sql.java`

```java
static final String INSERT_EXPENSE_BATCH = Sql.INSERT_EXPENSE;   // same statement

static final String SELECT_TOTALS_BY_CATEGORY = """
    SELECT category, SUM(amount_cents) AS total, COUNT(*) AS entries
    FROM expenses
    WHERE spent_on BETWEEN ? AND ?
    GROUP BY category
    ORDER BY total DESC, category ASC
    """;

static final String SELECT_TOP_EXPENSES = """
    SELECT id, amount_cents, category, description, spent_on, created_at
    FROM expenses
    WHERE spent_on BETWEEN ? AND ?
    ORDER BY amount_cents DESC, spent_on DESC, id DESC
    LIMIT ?
    """;
```

`ORDER BY total DESC, category ASC` — the second key is sprint 05's requirement. Two
categories with identical spending must come back in a defined order, or the SQL and
stream summaries will disagree intermittently and you will not be able to reproduce it.

`LIMIT ?` is a bound parameter. Limits are values, so this is allowed — unlike `ORDER BY
?`, which is structure and is not.

---

## `ExpenseStore` — the interface grows

```java
/**
 * Inserts every expense in one transaction.
 * Returns the number inserted, or 0 if the operation was cancelled.
 * Rolls back entirely on cancellation or on any failure.
 *
 * @param onProgress called with the running count after each row
 * @param cancelled  polled between rows; true aborts and rolls back
 */
int addAll(List<Expense> expenses, IntConsumer onProgress, BooleanSupplier cancelled);

/** One row per category with spending in the range. Never null. */
List<CategorySpend> totalsByCategory(LocalDate from, LocalDate to);

/** The `limit` largest expenses in the range, largest first. Never null. */
List<Expense> findTop(LocalDate from, LocalDate to, int limit);
```

Add a convenience overload so tests and sprint 11 are not cluttered with no-op lambdas:

```java
default int addAll(List<Expense> expenses) {
    return addAll(expenses, i -> {}, () -> false);
}
```

---

## `JdbcExpenseStore.addAll`

The one method in the project that manages a connection by hand. Read it slowly.

```java
@Override
public int addAll(List<Expense> expenses, IntConsumer onProgress, BooleanSupplier cancelled) {
    if (expenses.isEmpty()) return 0;

    Connection c = db.open();
    try {
        c.setAutoCommit(false);

        int inserted = 0;
        try (PreparedStatement ps = c.prepareStatement(Sql.INSERT_EXPENSE)) {
            for (Expense e : expenses) {

                if (cancelled.getAsBoolean()) {
                    c.rollback();
                    return 0;
                }

                bind(ps, e);
                ps.addBatch();
                inserted++;

                if (inserted % BATCH_SIZE == 0) ps.executeBatch();
                onProgress.accept(inserted);
            }
            ps.executeBatch();          // whatever is left in the queue
        }

        c.commit();
        return inserted;

    } catch (SQLException ex) {
        try { c.rollback(); } catch (SQLException ignored) { }
        throw new StoreException("failed to import " + expenses.size() + " expenses", ex);
    } finally {
        try { c.setAutoCommit(true); } catch (SQLException ignored) { }
        try { c.close(); }            catch (SQLException ignored) { }
    }
}

private static final int BATCH_SIZE = 500;
```

### Details worth noticing

| Detail | Why |
|---|---|
| The connection is **not** in try-with-resources | Rollback must happen before close, and try-with-resources closes first. |
| The `PreparedStatement` **is** | It has no ordering requirement against the rollback. |
| Rollback failures are swallowed | Throwing from a `catch` would replace the real cause with a secondary one. Log it in a real system; here, a comment saying so is enough. |
| `return 0` on cancel | Distinguishable from a partial success, because a partial success cannot happen. |
| `bind(ps, e)` is extracted | The same six `setX` calls as `add`. Two copies would drift. |

### The seeding helper for the performance fixture

Put it in the **test** sources, not in `main` — it exists to build a 50 000-row database
for sprint 15's responsiveness check:

```java
static List<Expense> randomExpenses(int count, YearMonth month, long seed) {
    Random random = new Random(seed);
    List<Expense> out = new ArrayList<>(count);
    Category[] categories = Category.values();
    int days = month.lengthOfMonth();

    for (int i = 0; i < count; i++) {
        out.add(Expense.create(
            Money.fromCents(random.nextInt(100, 20_000)),
            categories[random.nextInt(categories.length)],
            "generated " + i,
            month.atDay(random.nextInt(1, days + 1))));
    }
    return out;
}
```

Seeded, so a performance problem is reproducible. Note this generates *past* months only
— `Expense.create` rejects future dates, so seeding the current month will fail on any
day that is not the last of the month.

---

## `totalsByCategory`

```java
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
```

`SUM` returns `NULL` when no rows match — but with `GROUP BY` there simply are no groups,
so the loop does not execute and you get an empty list. Worth knowing the difference:
`SELECT SUM(x) FROM t WHERE false` returns one row containing `NULL`, while
`SELECT SUM(x) FROM t WHERE false GROUP BY y` returns no rows at all.

## `findTop`

Standard shape, three parameters, reuses `map`:

```java
ps.setString(1, from.toString());
ps.setString(2, to.toString());
ps.setInt   (3, limit);
```

Guard `limit <= 0` with an `IllegalArgumentException` before touching the database.
`LIMIT 0` returns nothing and `LIMIT -1` means "no limit" in SQLite — neither is what a
caller asking for "the top -1 expenses" meant.
