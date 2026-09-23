# Sprint 09 · Transactions and aggregates

**Time:** ~2 hours · **Prerequisites:** 08 · **Produces:** `addAll`, `totalsByCategory`, `findTop`, `CategorySpend`

---

## Purpose

Three things the store still cannot do, and each is the last piece some later sprint
needs:

- **`addAll`** — insert many expenses so that either all of them land or none do. Sprint
  19's CSV import depends on it, and so does the 50 000-row fixture you will need to
  prove the window stays responsive.
- **`totalsByCategory`** — let the *database* do the grouping. Sprint 11 compares this
  against a Java implementation of the same thing.
- **`findTop`** — the N largest expenses, with the ordering and the limit done in SQL.

The first one is the substantial one. It is where you meet transactions properly, and
where the store API has to be shaped around a requirement rather than around what is
convenient.

## New concepts

### 1. A transaction is a unit of "all or nothing"

By default JDBC is in **autocommit**: every statement commits as it runs. Insert 500
rows in a loop and you have 500 independent commits — so a failure on row 300 leaves 299
rows behind and no way to know which.

```java
c.setAutoCommit(false);     // from here, nothing is permanent
// ... many statements ...
c.commit();                 // now all of them are, at once
// or
c.rollback();               // now none of them ever were
```

The specification's requirement — *"one bad line rolls the whole import back, the
database is unchanged"* — is precisely this, and it cannot be built out of individual
`add` calls no matter how carefully you write the loop.

The shape, which you saw once in sprint 06's migrator:

```
setAutoCommit(false)
try      → statements → commit()
catch    → rollback() → throw
finally  → setAutoCommit(true), close
```

Restoring autocommit in the `finally` matters when connections are pooled and reused.
Here every connection is closed immediately so it is belt and braces — but it is the
correct habit and costs one line.

### 2. Why `addAll` cannot be a loop over `add`

```java
for (Expense e : expenses) store.add(e);      // 500 connections, 500 commits
```

Each `add` opens its own connection, commits, and closes. There is no shared
transaction to roll back. The operation has to own the connection for its whole
duration, which means it has to be **one method on the store**, not something a caller
assembles.

This is a general point about designing a storage interface: the unit of transaction is
part of the contract. If the caller needs atomicity across several writes, the interface
has to offer an operation at that granularity.

### 3. Cancellation is polled, not interrupted

Sprint 19 puts a Cancel button on the import dialog. The obvious mechanism —
`thread.interrupt()` — does not work: **JDBC calls do not respond to interruption.** The
thread stays inside `executeUpdate` until the driver returns.

So the operation checks for itself, between rows:

```java
int addAll(List<Expense> expenses, IntConsumer onProgress, BooleanSupplier cancelled)
```

```java
for (int i = 0; i < expenses.size(); i++) {
    if (cancelled.getAsBoolean()) { c.rollback(); return 0; }
    ...
    onProgress.accept(i + 1);
}
```

Two **functional interfaces** from `java.util.function`:

| Type | Shape | Here it is |
|---|---|---|
| `BooleanSupplier` | `() -> boolean` | sprint 19's `task::isCancelled` |
| `IntConsumer` | `(int) -> void` | sprint 19's progress bar update |

The store knows neither of those things exist. It knows it can ask a question and
report a number. In tests you pass `() -> false` and `i -> {}`; in sprint 19 you pass
the real ones. That is the whole benefit — the store is testable without a UI, and the
UI is swappable without touching the store.

Note that cancellation **rolls back**. A cancelled import that left half its rows behind
would be worse than one that failed, because the user believes they undid it.

### 4. Batching

```java
for (Expense e : expenses) {
    bind(ps, e);
    ps.addBatch();
    if (++count % 500 == 0) ps.executeBatch();
}
ps.executeBatch();      // the remainder
```

`addBatch` queues the bound parameters; `executeBatch` sends them together. The win is
round trips: one send of 500 rows instead of 500 sends of one.

Flush every 500 rather than batching all 50 000 at once — the queue is held in memory,
and an unbounded one is a memory problem waiting for a big enough file. The batching is
inside the transaction either way, so atomicity is unaffected.

### 5. Letting the database aggregate

```sql
SELECT category, SUM(amount_cents) AS total, COUNT(*) AS entries
FROM expenses
WHERE spent_on BETWEEN ? AND ?
GROUP BY category
```

A month with 4 000 expenses across 7 categories returns **7 rows**, not 4 000. The
database reads the rows; only the answer crosses the boundary.

Sprint 11 implements the same summary in Java, over the full row list, and compares.
That is the point of the exercise — here you are just building one of the two sides.

## What you build

- `CategorySpend` — a small record, the shape of one `GROUP BY` row
- `ExpenseStore`: `addAll`, `totalsByCategory`, `findTop`
- The implementations, plus a seeding helper for the 50 000-row fixture

## Definition of done

- [ ] A failure partway through `addAll` leaves the table exactly as it was
- [ ] Cancelling partway through leaves the table exactly as it was
- [ ] `totalsByCategory` returns one row per category that has spending
- [ ] `findTop(from, to, 5)` returns 5 rows ordered by amount descending, with `LIMIT`
      in the SQL
- [ ] 50 000 rows insert in a few seconds, not a few minutes

---

## Reading check

1. `setAutoCommit(true)` is in the `finally`, after `close()` would have happened
   anyway. Name a situation where omitting it causes a real bug.
2. `addAll` takes a `BooleanSupplier` rather than a `volatile boolean` field. What does
   that buy, given both would work?
3. `executeBatch` is called inside the loop *and* after it. What goes wrong if you drop
   either call?
4. `findTop` could be `find(...)` followed by `.sorted().limit(5)` in Java. The
   specification forbids it. On a 50 000-row month, what is the concrete cost?
