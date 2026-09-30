# Sprint 09 · Reading check answers

Answers to the four questions at the end of `README.md`.

Questions 1, 3 and 4 were checked by running code against the project's driver and the
50 000-row fixture, rather than answered from documentation alone.

---

## 1. `setAutoCommit(true)` is in the `finally`, after `close()` would have happened anyway. Name a situation where omitting it causes a real bug.

**A connection pool.** With a pool, `close()` does not close the connection. It hands it back
to the pool, still open, still configured, to be given to the next piece of code that asks for
one. Whatever state you leave it in, the next borrower inherits.

Leave it with autocommit **off**, and the next borrower - which knows nothing about this import
- gets a connection that silently never commits:

```java
// later, somewhere else, borrowing the same pooled connection
try (Connection c = pool.getConnection();
     PreparedStatement ps = c.prepareStatement(Sql.INSERT_EXPENSE)) {
    bind(ps, expense);
    ps.executeUpdate();          // succeeds, returns 1
}                                // "close" = back to the pool. Never committed.
```

The code is correct for a normal connection. It returns normally, the row count says 1, and
the expense is shown in the table from the same connection. Then, depending on the pool:

- the pool rolls back when the connection is returned, and the expense **vanishes**; or
- the next borrower's `commit()` - for something unrelated - commits this insert as well, so
  data appears as a side effect of a different operation, and two operations the user thought
  were separate now succeed or fail together; or
- the open transaction holds SQLite's write lock, and every other writer waits
  `busy_timeout` and then fails with `SQLITE_BUSY`.

None of those point back at the import that forgot to restore autocommit. That is why it is a
habit, not something decided case by case: the code that leaves the mess and the code that
suffers it are different code, often written months apart.

This project has **no pool** - every `open()` makes a new connection and `close()` really
closes it - so here the line changes nothing. It stays because sprint 20's optional Postgres
swap is where a pool such as HikariCP would come in, and the day that happens the line becomes
necessary without anyone remembering to add it. (Good pools reset autocommit themselves when a
connection comes back. Relying on that is relying on configuration you cannot see from here.)

### The trap in the other direction - checked

Restoring autocommit is only safe **after** the transaction has ended. With this driver,
`setAutoCommit(true)` in the middle of a transaction **commits** it - which is what the JDBC
specification says should happen. So `SPEC.md`'s shape has a bug of its own:

```java
} catch (SQLException ex) {      // only SQLException rolls back
    ...
} finally {
    c.setAutoCommit(true);       // an uncaught RuntimeException gets here first - and commits
}
```

If the progress callback throws after the first batch of 500 has been sent, there is no
rollback, and the `finally` commits those 500 rows. Tested by narrowing the `catch` on purpose:
`anExceptionFromTheProgressCallbackRollsBack` failed with *expected 0 but was 500*. The
project's `addAll` rolls back on any exception, so by the time the `finally` runs there is no
open transaction to commit.

## 2. `addAll` takes a `BooleanSupplier` rather than a `volatile boolean` field. What does that buy, given both would work?

A `volatile boolean` field would look like this:

```java
public final class JdbcExpenseStore implements ExpenseStore {
    private volatile boolean cancelRequested;         // set by the Cancel button
    public void cancel() { cancelRequested = true; }
    ...
}
```

(`volatile` makes a write by one thread - the UI's - visible to a read on another - the
import's. Without it, the import thread may never see the change.)

It works. The `BooleanSupplier` is better in several ways:

### 1. The flag belongs to one call, not to the whole store

A field is shared by **every** call to the store. One store object is used by the whole app,
so:

- Two imports at once share one flag. Cancelling one cancels both.
- The flag has to be **reset** before the next import. Forget, and every import after the
  first cancelled one is cancelled instantly, before its first row. Reset it at the start of
  `addAll`, and a Cancel pressed just before the import began is silently lost.
- The store now has **mutable state**, in a class that until now had exactly one field, the
  `Database`, set once. A stateless store is trivially safe to share between threads; one
  with a flag is not.

A `BooleanSupplier` is a parameter: each call gets its own, and it disappears when the call
returns. There is nothing to reset and nothing to share.

### 2. The store does not decide what "cancelled" means

The store asks a question; the caller decides the answer. In sprint 19 it is `task::isCancelled`
- JavaFX's `Task` already tracks cancellation, and the store simply asks it. Nothing needs to
copy that state into a field on the store and keep the two in step. Tomorrow it could be a
timeout (`() -> System.nanoTime() > deadline`), a row limit, or a combination - and `addAll`
does not change.

### 3. Tests control it exactly

```java
store.addAll(batch, seen::set, () -> seen.get() >= 600);
```

The cancellation is tied to the progress counter, so it lands after exactly row 600, every
run, on every machine. With a field, a test would have to set it from another thread at the
right moment - usually with a `sleep` - which is the flaky "cancel after 50 milliseconds" test
`TESTS.md` warns about. Here, one lambda, no threads.

### 4. The interface stays clean

`cancel()` would have to be added to `ExpenseStore` for callers to use it - a method that only
means something while an `addAll` is running, on an interface about storage. The supplier keeps
cancellation where it belongs: in the one method that can be cancelled.

**The general shape:** pass behaviour in as a parameter, rather than having the object hold
state that callers poke. The object stays simple, and every caller can plug in what it needs.

(The thread-safety requirement does not disappear - it moves. `task::isCancelled` must be safe
to call from the import thread, and JavaFX's `Task` makes it so. But that is the caller's
concern, next to the code that knows about threads, not the store's.)

## 3. `executeBatch` is called inside the loop *and* after it. What goes wrong if you drop either call?

Both were dropped on purpose and the tests re-run.

### Drop the call **after** the loop: rows go missing

The in-loop call only fires when the count reaches a multiple of 500. Whatever has been queued
since the last multiple is only sent by the final call.

| Rows | In-loop flushes | Left in the queue | Without the final call |
|---|---|---|---|
| 1 200 | at 500 and 1 000 | 200 | **1 000 inserted, 200 lost** |
| 100 | none | 100 | **nothing inserted at all** |
| 1 000 | at 500 and 1 000 | 0 | all 1 000 - **the bug hides** |

And it is silent. The queued rows are never sent, so they never fail; `commit()` commits what
was sent; and `addAll` returns `inserted` - the number of rows *bound*, not sent - so it
reports 1 200 for an import that saved 1 000. The user sees "1 200 imported", and 200 expenses
are gone.

This is why `addAllCrossesTheBatchBoundary` uses 1 200, not 1 000: with an exact multiple of
500, the in-loop flush happens to send everything and the test cannot fail. With the final
call removed, 9 tests failed - including the plain 100-row `addAllInsertsEveryRow`, which lost
every row.

### Drop the call **inside** the loop: nothing visibly breaks

With the in-loop flush removed, every row is queued and all 50 000 are sent by the final call.
It is still one transaction, so atomicity is unaffected - and **all 208 tests passed**. The
50 000-row import took the same ~2 seconds.

What goes wrong is **memory**. The whole import sits in the driver's queue before anything is
sent - every bound parameter of every row. At 50 000 small rows that is a few megabytes and
invisible. At a few million rows from a large CSV, it is an `OutOfMemoryError`, and the
failure depends on the size of the user's file rather than on anything a test would try.

The honest conclusion: **no test in this project can see the in-loop flush.** It protects
against a file much larger than any test uses. That guarantee comes from reading the code, and
it is worth knowing which of your guarantees are that kind.

(The other thing the in-loop flush is often said to buy - speed, from fewer round trips - did
not show up here. SQLite runs inside the same process, so a "round trip" is a function call.
Against a network database each round trip crosses the wire, and batching matters much more.)

## 4. `findTop` could be `find(...)` followed by `.sorted().limit(5)` in Java. The specification forbids it. On a 50 000-row month, what is the concrete cost?

Measured on the 50 000-row fixture that the slow test builds (all rows in August 2025), best
of five runs each:

| | `find` + `.sorted().limit(5)` | `findTop(from, to, 5)` |
|---|---|---|
| Rows read from SQLite into Java | 50 000 | 5 |
| `Expense` objects built | 50 000 | 5 |
| Heap for the result, roughly | **91 MB** | a few KB |
| Time | **~265 ms** | **~62 ms** |
| Answer | same five amounts | same five amounts |

Where the cost goes, in the Java version:

1. **Every row crosses into Java.** For each of the 50 000 rows the driver copies six columns
   out of SQLite and builds Java `String`s.
2. **Every row is mapped.** Each goes through `map`: `Money.fromCents`, `Category.parse`, two
   date parses, and `Expense.restore`, which runs the full validation. 50 000 times, to keep 5.
3. **All of it is held at once.** `find` returns a complete list, about 91 MB here, before the
   first comparison. `List.copyOf` copies the list of references once more.
4. **Then it is sorted** - about 800 000 comparisons of `BigDecimal`s - and 49 995 of the
   objects are thrown away, for the garbage collector to clean up.

In the SQL version, SQLite does the reading and ordering in C, over integers, and only five
rows are ever mapped.

### Why that matters in this application

- **The UI.** From sprint 15 the summary is computed on a background thread, but a quarter of a
  second of work plus a 91 MB allocation every time the user changes month is exactly the kind
  of load that makes a desktop app feel sluggish - and causes garbage-collection pauses that do
  hit the UI thread.
- **It scales with the wrong number.** The SQL version's cost to Java is fixed by `limit`: five
  rows, whether the month has 50 or 5 million expenses. The Java version's cost grows with the
  size of the month, so it gets slower exactly as the user's data grows.

### What would make `findTop` faster still

62 ms is not free: SQLite still reads all 50 000 rows of the month to find the largest, because
no index covers `amount_cents`. An index on `(spent_on, amount_cents)` would let it read far
fewer. It is not worth adding for a list of five - but it is the right next question to ask,
and it is a question you can only ask once the work is in SQL. Sorted in Java, the database
never gets the chance to help.
