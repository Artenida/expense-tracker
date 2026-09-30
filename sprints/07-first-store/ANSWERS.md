# Sprint 07 · Reading check answers

Answers to the four questions at the end of `README.md`.

---

## 1. `ps.setString(1, id)` - why `1` and not `0`?

**Because JDBC numbers parameters - and columns - the way SQL does, starting from 1.**

The `1` is not an index into a Java array. It means *the first `?` in the statement*:

```java
// INSERT INTO expenses (id, amount_cents, category, ...) VALUES (?, ?, ?, ...)
//                                                               1  2  3
ps.setString(1, e.id());
ps.setLong(2, Money.toCents(e.amount()));
ps.setString(3, e.category().name());
```

SQL has always counted from 1. `ORDER BY 1` sorts by the first column. `substr('hello', 1, 2)`
starts at the first character. SQLite's own C function for binding a value,
`sqlite3_bind_text`, takes a 1-based position. JDBC, designed in 1997 as a thin Java layer
over databases, copied the database convention rather than Java's.

The same rule applies when reading: `rs.getInt(1)` is the first column of the result. That
is why sprint 06's `currentVersion` reads `rs.getInt(1)` for `SELECT COALESCE(MAX(version), 0)`.

**What happens if you use 0 - checked against this project's driver.** It fails at once,
but not the way you might expect:

```
java.lang.ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 2
    at org.sqlite.core.CorePreparedStatement.batch(...)
    at org.sqlite.jdbc3.JDBC3PreparedStatement.setString(...)
```

Two things in that message are worth noticing:

- **`Index -1`.** The driver subtracts 1 to turn your 1-based position into its own
  0-based array slot, so `0` becomes `-1`. You can see the translation happening.
- **It is not a `SQLException`.** The JDBC documentation says a bad index should throw
  `SQLException`; this driver lets a raw `ArrayIndexOutOfBoundsException` escape instead.
  That matters here: `JdbcExpenseStore` only catches `SQLException`, so this would
  *not* be wrapped in a `StoreException` - it would fly straight past. It is a bug in the
  code, not a database failure, so that is arguably fine; but it is another case of
  checking what a driver really does rather than what the interface promises.

The dangerous version is the *silent* off-by-one: starting from 1 correctly but then
numbering the rest wrong. Swap the positions of the amount and the category and SQLite,
whose columns are loosely typed, stores it without complaint - checked:

```
amount_cents = 'GROCERIES'   (typeof: text)
category     = '2490'
```

Even `CHECK (amount_cents > 0)` passes, because in SQLite's comparison rules any text value
is greater than any number. `everyFieldSurvivesTheRoundTrip` is the test that would notice -
`Category.parse("2490")` would throw on the way back.

**The habit:** Java collections, arrays and strings count from 0. JDBC parameters and
columns count from 1. It is worth one comment the first time and never again.

## 2. `findById` returns `Optional<Expense>`. What would the signature have to be if it returned `Expense`, and what would every caller then have to remember?

**The signature would be `Expense findById(String id)`, and "not found" would have to be
either `null` or an exception. Either way, every caller must remember something the
compiler does not check.**

### Option A - return `null`

```java
/** Returns the expense, or null if there is none. */
Expense findById(String id);
```

Every caller must remember to check:

```java
Expense e = store.findById(id);
if (e == null) {
    ...
}
```

The **only** thing telling them is a comment. Forget the check - and it is easy, because
the method *usually* returns something - and the code works in every test with existing
ids, then throws `NullPointerException` somewhere later: in a table cell, in a total, in a
different class from the one that forgot. The stack trace points at where the `null` was
*used*, not where it came from.

It also breaks the project's rule: **no public method returns `null`.**

### Option B - throw

```java
/** @throws NotFoundException if there is none */
Expense findById(String id);
```

Now callers must remember to catch it, or to check first with an `exists(id)` method -
which takes two queries and leaves a gap in between where the row can be deleted. And it
uses an exception for something that is not exceptional. "The user deleted that expense in
another window" is a normal outcome of a lookup.

### With `Optional`

```java
Optional<Expense> findById(String id);
```

The possibility of "not found" is **in the type**. A caller *cannot* get an `Expense` out
without saying what happens when there is none:

```java
store.findById(id).ifPresent(table::select);                 // do something if found
Expense e = store.findById(id).orElseThrow();                  // "must exist" - a bug if not
String label = store.findById(id).map(Expense::description).orElse("(deleted)");
```

Nothing to remember - the compiler will not let `Optional<Expense>` be used as an
`Expense`. That is the difference between a rule in a comment and a rule in a type.

**What `Optional` does not solve:** a caller can still write `.get()` or `.orElseThrow()`
without thinking. But then the decision is written right there in the code, visible to a
reviewer, rather than being an absent `if`.

## 3. The mapper reads columns by name. Give a concrete change to the schema that would break an index-based mapper and leave a name-based one working.

**Change the column list in the `SELECT` - for example, to add the `note` column that
sprint 06's V2 created.**

The mapper reads positions from the *result*, and the result's columns are whatever the
`SELECT` lists, in that order. Today:

```sql
SELECT id, amount_cents, category, description, spent_on, created_at FROM expenses
--     1   2             3         4            5         6
```

An index-based mapper:

```java
Expense.restore(
    rs.getString(1),                            // id
    Money.fromCents(rs.getLong(2)),             // amount_cents
    Category.parse(rs.getString(3)),            // category
    rs.getString(4),                            // description
    LocalDate.parse(rs.getString(5)),           // spent_on
    Instant.parse(rs.getString(6)));            // created_at
```

Now a later sprint starts using `note`, and someone adds it where it reads naturally, next
to the description:

```sql
SELECT id, amount_cents, category, description, note, spent_on, created_at FROM expenses
--     1   2             3         4            5     6         7
```

Every position from 5 onward has moved:

- `rs.getString(5)` is now `note`, which is usually `NULL`, and `LocalDate.parse(null)`
  throws `NullPointerException`.
- If a note *is* present, `LocalDate.parse("paid cash")` throws `DateTimeParseException`.
- `rs.getString(6)` is now `spent_on` - `"2026-09-15"` - and `Instant.parse` rejects it.

The name-based mapper does not care. `rs.getString("spent_on")` finds the column called
`spent_on` wherever it is.

### The quieter version, which is worse

Two columns *of the same type* trading places:

```sql
SELECT id, amount_cents, category, spent_on, description, created_at ...
```

Both are text, so nothing throws. The index-based mapper reads the date as the description
and `"weekly shop"` as the date - then `LocalDate.parse("weekly shop")` throws. But if both
had been plain text columns, say `description` and a future `payee`, the values would be
silently **swapped** in every expense, and the tests that only check "something came back"
would pass.

### The other direction

The same thing happens with the table's own column order if a query uses `SELECT *`: a
table rebuilt with its columns in a different order (SQLite's usual way of changing a
column's type is to create a new table and copy the data across) changes every position
at once. That is one of the reasons `Sql.java` never uses `SELECT *` - and names in the
mapper are the second lock on the same door.

## 4. You return a `List<Expense>` from a query and the connection closes when the method returns. Why do the `Expense` objects still work, when a `ResultSet` would not?

**Because an `Expense` holds its own copies of the values. A `ResultSet` holds a live link
to the database, and closing the connection cuts it.**

### What a `ResultSet` really is

A `ResultSet` does not contain the rows. It is a **cursor**: a handle on a statement that
SQLite is still running, which produces the next row each time you call `next()`. Behind
it is a native SQLite statement object, which belongs to the connection.

When the try-with-resources closes the connection, the statement and the cursor are closed
with it. A `ResultSet` handed back to a caller would be a handle on something that no
longer exists:

```java
ResultSet leaked = store.findAllRaw();   // hypothetically
leaked.next();                           // SQLException: ResultSet is closed
```

It might not even fail cleanly. Some drivers hand back the rows already fetched and then
fail partway through the list - a bug that depends on how many rows there are.

### What an `Expense` really is

`map(rs)` reads each value out of the current row and passes it to `Expense.restore`:

```java
rs.getString("id")                            ->  a new String
Money.fromCents(rs.getLong("amount_cents"))   ->  a new BigDecimal
Category.parse(rs.getString("category"))      ->  one of the seven enum constants
LocalDate.parse(rs.getString("spent_on"))     ->  a new LocalDate
```

Each of those is an ordinary Java object living in the JVM's memory. None of them has any
reference back to the `ResultSet`, the statement or the connection. Once `map` returns, the
`Expense` is complete on its own. Closing the connection frees SQLite's resources and
touches nothing the `Expense` points at.

And because `Expense` is **immutable** (sprint 04), those copies can never change
afterwards - not by the database, not by anybody.

### Why this is the design, not an accident

**Copy the data out while the resource is open, then let the resource go.** That is the
whole reason the store returns domain objects and never JDBC types. It is also why the
interface can hide the database entirely: callers receive plain Java values they can keep
for as long as they like - store in a table model, pass to another thread in sprint 15 -
with no idea a connection ever existed.

The cost is that all the rows are held in memory at once. For thousands of expenses that is
nothing. For millions it would matter, and the answer then is paging (fetch a page of rows
at a time), not handing out cursors.
