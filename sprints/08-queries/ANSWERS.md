# Sprint 08 · Reading check answers

Answers to the four questions at the end of `README.md`.

Questions 1 and 2 were checked by running code against the project's driver,
`sqlite-jdbc` 3.46.1.3. Question 1 does not behave the way `README.md` says it does.

---

## 1. You bind parameter 3 to `"GROCERIES"` and forget parameter 4. What happens - at compile time, and at run time?

### At compile time: nothing

The compiler has no idea. To Java, the SQL is just a `String` constant; the `?`s inside it
are characters. `ps.setString(3, ...)` is a method call with an `int` and a `String`, and
it is equally valid whether the statement has two placeholders or twenty. No IDE warning,
no compiler error. Nothing in Java connects the number of `?`s to the number of `set` calls.

### At run time: `README.md` says it throws - with this driver, it does not

`README.md` predicts `SQLException: not all parameters set`. Some drivers do that. This
project's does not. Checked:

```java
PreparedStatement ps = c.prepareStatement(
        "SELECT ... FROM e WHERE (? IS NULL OR category = ?)");
ps.setString(1, "GROCERIES");
// parameter 2 never bound
ps.executeQuery();         // no exception
```

The query runs and returns **zero rows**. No exception, no warning.

What happens underneath: SQLite's own rule is that **a parameter that was never bound is
`NULL`**. The driver passes that rule straight through. So the real condition is:

```sql
('GROCERIES' IS NULL OR category = NULL)
--  false                 NULL - "= NULL" is never true in SQL
```

`category = NULL` is not true for any row - in SQL, comparing anything to `NULL` with `=`
gives `NULL`, which a `WHERE` treats as false. So no row matches.

### Why that is worse than an exception

An exception would fail the first time the code ran. An empty result looks like a month in
which you did not buy any groceries. The table would be blank, the summary would say 0.00,
and nothing would point at the missing line.

The inconsistent-binding cases, also checked, are silent too:

| Parameter 3 | Parameter 4 | Result |
|---|---|---|
| `'GROCERIES'` | not bound | nothing |
| `NULL` | `'GROCERIES'` | **everything** - the category filter is ignored |
| `'GROCERIES'` | `NULL` | nothing |

`README.md`'s warning that inconsistent binding "silently returns everything" is right. Its
warning that a missing binding throws is not, for this driver - here it silently returns
nothing.

### What catches it

Only a test that looks at the **result**. `findWithACategoryFiltersToIt` expects exactly the
two September groceries rows; `findWithoutACategoryReturnsAll` expects all four. Forgetting
parameter 4 would turn the first into an empty list and fail it at once.

This is a second example of the lesson from sprints 06 and 07: **the JDBC interface
describes what a driver *should* do; only running it tells you what this one *does*.**

## 2. `BETWEEN ? AND ?` is bound with ISO date *strings*. Why does that compare correctly, and what would break if dates were stored as `15/09/2026`?

### Why ISO strings compare correctly

`spent_on` is a `TEXT` column, so SQLite compares values **as text**: character by
character, left to right, stopping at the first difference - the way a dictionary is
ordered.

ISO-8601 dates are written **most significant part first, every part a fixed width**:

```
2026-09-15
^^^^ ^^ ^^
year mo day
```

So the first character that differs between two dates is always in the most important
part that differs:

```
'2025-10-01'  vs  '2026-09-15'    first difference: 5 < 6 in the year  -> 2025 first
'2026-08-31'  vs  '2026-09-01'    first difference: 8 < 9 in the month -> August first
'2026-09-01'  vs  '2026-09-15'    first difference: 0 < 1 in the day   -> the 1st first
```

The text order and the date order are the same order. That is what makes `BETWEEN` on text
correct, and what lets the `spent_on` index work for date ranges. The zero-padding is
essential: without it, `'2026-9-15'` would sort *after* `'2026-10-01'`, because `'9' > '1'`.

### What breaks with `15/09/2026`

`dd/mm/yyyy` puts the **least** significant part first. Text comparison now looks at the
*day* first, then the month, and the year last. Checked, with the query for September 2026:

```sql
WHERE d BETWEEN '01/09/2026' AND '30/09/2026'
```

| Stored | A September 2026 date? | Matched by `BETWEEN` |
|---|---|---|
| `15/09/2026` | yes | yes |
| `30/09/2026` | yes | yes |
| `01/10/2025` | **no** - October of the year before | **yes** |
| `02/09/2027` | **no** - a year later | **yes** |
| `31/08/2026` | no | no |

The range `'01/09/2026'` to `'30/09/2026'` really means "any string starting with `01` to
`30`, roughly" - almost every day of every month of every year. `01/10/2025` is inside it
because `'01/1'` sorts after `'01/0'`; the year is never looked at.

And the ordering breaks too. `ORDER BY d DESC` gave:

```
31/08/2026  30/09/2026  15/09/2026  02/09/2027  01/10/2025
```

- sorted by day of the month, with the year of each date effectively random.

Everything that depends on dates would be wrong at once: the month filter, the newest-first
table, the summary totals. And nothing would throw - every query succeeds.

### The rule

Store dates in a format whose **text order is its date order**. ISO-8601 is that format -
it was designed so. `LocalDate.toString()` produces it and `LocalDate.parse()` reads it, and
neither depends on the machine's locale. The display format - `15/09/2026` for a user in
Europe, `09/15/2026` in the US - is a UI decision made at the last moment, never a storage
one.

## 3. `delete` returns `false`. Name two different situations that produce it, and say whether the caller can tell them apart.

**Two situations:**

1. **The expense never existed.** A made-up id, a typo, an id from a different database file,
   an id read from a CSV that was never imported.
2. **The expense existed but was already deleted.** The user clicked Delete twice quickly;
   another window deleted it first; another process (a second copy of the app, a script
   against the file) removed it. `deleteIsIdempotentInEffect` shows this one: the first delete
   returns `true`, the second `false`.

(A third, arguably: the id is right but someone *changed* it in the file, so no row has it any
more. From the database's point of view that is the same as case 1.)

**Can the caller tell them apart? No.**

Both run:

```sql
DELETE FROM expenses WHERE id = ?
```

and both get back `0` - zero rows changed. That is all `executeUpdate` reports. The database
keeps no record of rows that used to exist, so at the moment of the `DELETE` there is no
difference between "was never here" and "was here a second ago". The `boolean` faithfully
passes on exactly the information that exists, and no more.

**Does it matter?** For this app, no. The response to both is the same: the expense is not
there, so tell the user and refresh the table. Sprint 10's service will turn `false` into
one readable message.

**If it did matter,** the store would have to keep history it does not have now - for example
a **soft delete**: instead of removing the row, set a `deleted_at` column. Then "deleted" and
"never existed" are different facts in the data, and a query could tell them apart. That is a
real design, used where an audit trail matters. It is not free: every other query then needs
`WHERE deleted_at IS NULL`, and forgetting it once shows deleted expenses again.

**A note on timing.** Even with a `SELECT` first to check, the caller could not reliably tell
the cases apart: the row could be deleted by someone else *between* the `SELECT` and the
`DELETE`. The single `DELETE` and its row count is the only answer that is true at the moment
it is given.

## 4. The upsert uses `excluded.limit_cents`. What does `excluded` refer to, and what would `budgets.limit_cents` mean in the same clause?

```sql
INSERT INTO budgets (category, limit_cents, updated_at)
VALUES (?, ?, ?)
ON CONFLICT(category) DO UPDATE
  SET limit_cents = excluded.limit_cents,
      updated_at  = excluded.updated_at
```

### `excluded` - the row you tried to insert

When the `INSERT` hits a conflict, the new row is *excluded* from the table - it is not
inserted. SQLite makes that rejected row available, under the name **`excluded`**, to the
`DO UPDATE` clause. So:

- `excluded.limit_cents` = the value just bound to parameter 2 - the **new** limit
- `excluded.updated_at` = parameter 3 - the **new** timestamp

It is a pseudo-table: there is no table called `excluded` in the file, and it only exists
inside the `DO UPDATE`.

### `budgets.limit_cents` - the row already there

Inside `DO UPDATE`, the table's own name refers to the **existing** row, the one that caused
the conflict. So `budgets.limit_cents` is the **old** limit. (A bare `limit_cents` on the
right-hand side means the same thing.)

A worked example. The table holds a groceries budget of 400.00; the user sets 450.00:

```
existing row (budgets):   GROCERIES  40000  2026-09-01T10:00:00Z
attempted row (excluded): GROCERIES  45000  2026-09-20T10:00:00Z
```

| Clause | Result | Meaning |
|---|---|---|
| `SET limit_cents = excluded.limit_cents` | `45000` | replace with the new value - **what we want** |
| `SET limit_cents = budgets.limit_cents` | `40000` | set it to what it already is - **nothing changes** |
| `SET limit_cents = budgets.limit_cents + excluded.limit_cents` | `85000` | add the two together |

The second row is the bug to watch for. Written by mistake, it compiles, runs, and returns
without error - and every budget change is silently ignored after the first one.
`settingABudgetTwiceLeavesOneRowWithTheNewerValue` would catch it: it expects 450.00 and would
get 400.00.

The third row shows why both names exist: combining the old and new values is a real pattern
- counting page views, adding to a running total. Here we only ever want the new value, so
only `excluded` is used.
