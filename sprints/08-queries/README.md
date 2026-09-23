# Sprint 08 · Queries, updates and the budget store

**Time:** ~2.5 hours · **Prerequisites:** 07 · **Produces:** `find`, `update`, `delete`, `BudgetStore`, `JdbcBudgetStore`

---

## Purpose

Sprint 07 did one row at a time by primary key — the easy case. This sprint adds the
three shapes that make up the rest of the application: a query with **optional**
criteria, statements whose result is a **row count** rather than data, and an
**upsert** that has to work whether or not a row already exists.

Each one has a specific trap, and all three traps are the kind that produce code which
looks right and works on your test data.

## New concepts

### 1. An optional filter in a single statement

The window has a month picker and a category picker, and the category picker has an
"All" entry. The naive implementation builds different SQL for each case:

```java
String sql = "SELECT ... WHERE spent_on BETWEEN ? AND ?";
if (category != null) sql += " AND category = '" + category + "'";   // and there it is
```

Two statements to maintain, and a concatenation that is one careless edit away from
being an injection. The specification's version keeps it to one:

```sql
WHERE spent_on BETWEEN ? AND ?
  AND (? IS NULL OR category = ?)
```

When the third parameter is `NULL` the first half of the `OR` is true and the category
condition is skipped entirely. When it holds a value the second half applies.

**You must bind the category twice** — parameters 3 and 4 — with the same value.
Placeholders are positional; there is no way to say "the same one again". Forget the
fourth and you get `SQLException: not all parameters set`. Bind them inconsistently and
you get a filter that silently returns everything.

### 2. Binding `NULL`

```java
if (filter.category().isPresent()) {
    String name = filter.category().get().name();
    ps.setString(3, name);
    ps.setString(4, name);
} else {
    ps.setNull(3, Types.VARCHAR);
    ps.setNull(4, Types.VARCHAR);
}
```

`setNull` needs the SQL type as well as the position. Most drivers ignore it for a
plain `NULL`, but it is part of the JDBC contract and drivers that talk to a stricter
database do use it. This is the **only** place in the project where a parameter is
deliberately `null` — everywhere else a `null` reaching a store method is a bug.

### 3. `executeUpdate` returns a row count, and ignoring it is a bug

`executeQuery` returns a `ResultSet`. `executeUpdate` returns an `int`: how many rows
the statement changed.

```java
int changed = ps.executeUpdate();
return changed > 0;
```

For `add` in sprint 07 the count was ignorable — a failed insert throws. For `update`
and `delete` it is the **only** signal that the row you targeted was not there. Ignore
it and deleting an already-deleted expense reports success, the UI removes a row that
was already gone, and the specification's requirement — *"a row deleted by another
process reports a readable message rather than silently succeeding"* — is unmet.

### 4. Who decides that "not found" is an error?

The store returns `boolean`. It does **not** throw `ExpenseNotFoundException`.

That is a layering decision worth sitting with. The store's job is to report what
happened: the row was there, or it was not. Whether "not there" is an error depends on
what the caller was trying to do — sprint 10's `ExpenseService.delete` treats it as one,
but a hypothetical "delete if present" would not. Push the exception down into the store
and you have baked one caller's policy into the storage layer.

The general shape: **the lower layer reports facts, the layer above applies policy.**

### 5. Upsert

```sql
INSERT INTO budgets (category, limit_cents, updated_at) VALUES (?, ?, ?)
ON CONFLICT(category) DO UPDATE SET limit_cents = excluded.limit_cents,
                                    updated_at  = excluded.updated_at
```

One statement that inserts when the category is new and updates when it is not.
`excluded` is a pseudo-table holding the row that *would have been* inserted, so
`excluded.limit_cents` is the new value.

The alternative — `SELECT`, then `INSERT` or `UPDATE` depending — is two round trips
with a race between them, and needs a transaction to be correct. The upsert is atomic
because it is one statement.

## Ordering

```sql
ORDER BY spent_on DESC, created_at DESC, id DESC
```

`spent_on DESC` is "newest first", which is what the specification asks for. The other
two are tiebreakers, and they exist because **ties are common** — several expenses on
the same day is the normal case, not the edge case.

Without a full tiebreak the database may return same-day rows in any order, and *a
different order on different runs*. A table that reshuffles when you change months and
change back looks like a bug even though every row is correct.

`created_at DESC` is the meaningful one. `id DESC` is the final guarantee of a total
order — ids are unique, so no tie can survive it.

> This is where the original specification's `exp-0001` id scheme would have hurt. Ids
> are `TEXT`, so `id DESC` sorts lexically: `exp-9999` would come after `exp-10000`.
> With `created_at` in front of it and UUIDs instead of a counter, the ordering is
> stable and the lexical comparison never decides anything meaningful.

## What you build

- `ExpenseStore`: `find`, `update`, `delete` added to the interface
- `JdbcExpenseStore`: the three implementations
- `BudgetStore` + `JdbcBudgetStore`: `upsert`, `findAll`, `findByCategory`

## Definition of done

- [ ] `find` with a category returns only that category; without one returns all
- [ ] A month's query never returns a row from an adjacent month, including at the
      boundaries (the 1st and the last day)
- [ ] `update` and `delete` return `false` for an unknown id
- [ ] Spec test 7: setting a budget for the same category twice leaves one row with the
      newer value
- [ ] Two expenses on the same day come back in a stable order across repeated runs

---

## Reading check

1. You bind parameter 3 to `"GROCERIES"` and forget parameter 4. What happens — at
   compile time, and at run time?
2. `BETWEEN ? AND ?` is bound with ISO date *strings*. Why does that compare correctly,
   and what would break if dates were stored as `15/09/2026`?
3. `delete` returns `false`. Name two different situations that produce it, and say
   whether the caller can tell them apart.
4. The upsert uses `excluded.limit_cents`. What does `excluded` refer to, and what
   would `budgets.limit_cents` mean in the same clause?
