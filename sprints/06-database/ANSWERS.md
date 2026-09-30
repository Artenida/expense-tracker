# Sprint 06 · Reading check answers

Answers to the four questions at the end of `README.md`.

Questions 2 and 3 depend on how the SQLite driver behaves, so they were checked by running
code against the project's own driver, `sqlite-jdbc` 3.46.1.3, rather than answered from
the documentation alone. Both turned out more interesting than `SPEC.md` suggests.

---

## 1. Two resources in one try-with-resources. The body throws, and closing the *first* resource also throws. Which exception does the caller see, and what happens to the other one?

**The caller sees the body's exception. The close failure is attached to it as a
*suppressed* exception - kept, but not in charge.**

```java
try (Connection c = db.open();                     // first resource
     PreparedStatement ps = c.prepareStatement(SQL)) {  // second resource
    ps.executeUpdate();                            // throws A
}                                                  // closing c throws B
```

What happens, in order:

1. The body throws **A**.
2. Resources close in **reverse order of declaration**: `ps` first, then `c`. The
   statement is closed before the connection it came from.
3. `ps.close()` succeeds.
4. `c.close()` throws **B**.
5. Java calls `A.addSuppressed(B)` and throws **A**.

So the caller catches **A**, and **B** is inside it:

```java
catch (SQLException a) {
    a.getSuppressed()      // [B]
}
```

and the stack trace prints both:

```
java.sql.SQLException: A - the real problem
    at ...
    Suppressed: java.sql.SQLException: B - the close failure
        at ...
```

**Why A wins.** A is the *cause* - the thing that actually went wrong. B is very likely a
*consequence* of A: a connection in a broken state is exactly the kind that fails to
close. If B replaced A, you would spend your debugging time on the symptom.

**Every resource is still closed.** A failure closing one does not stop the others from
being closed. Here `ps` had already closed, but if `ps.close()` had thrown too, `c.close()`
would still have been attempted, and both failures would be suppressed onto A.

**If the body had *not* thrown,** there is nothing to attach B to, so B itself is thrown
to the caller.

### Why this is an improvement

Before Java 7, the same thing was written with `finally`:

```java
Connection c = db.open();
try {
    ...                       // throws A
} finally {
    c.close();                // throws B - and A is lost for ever
}
```

An exception thrown from `finally` **replaces** the one already in flight. A is discarded
without a trace, and the stack trace shows only the close failure. Try-with-resources was
designed to fix exactly this.

The migrator's `rollbackQuietly` does the same thing by hand: a failed rollback is added to
the original exception with `addSuppressed` rather than replacing it.

## 2. Why does `PRAGMA journal_mode=WAL` have to run outside a transaction?

### The reason

The journal mode is **how SQLite makes a transaction safe**. In the default `delete` mode, a
transaction copies the original pages to a rollback journal (`expenses.db-journal`) before
changing them; if anything goes wrong, SQLite puts the originals back. In WAL mode it does
the opposite: new pages are appended to a write-ahead log (`expenses.db-wal`) and the main
file is left alone until a checkpoint.

A transaction that is already open is part-way through using one of those mechanisms. If
the mode changed halfway, the changes made so far would be protected by one scheme and the
rest by another, and there would be no single consistent way to commit or roll back. So
SQLite refuses to change the mode while a transaction is active. Switching into WAL also
needs the file to itself, to set up the `-wal` and `-shm` files.

### What actually happens - checked, not assumed

The natural guess is that SQLite throws an error. With this driver, it **does not**:

```java
c.setAutoCommit(false);                              // begin a transaction
s.execute("CREATE TABLE t(x)");                      // the transaction is now active
ResultSet rs = s.executeQuery("PRAGMA journal_mode = WAL");
rs.next();
rs.getString(1)                                      // "delete"
```

The pragma **returns the mode it is actually in - `delete` - and carries on.** No exception,
no warning. It quietly did nothing.

That is worse than an error. If `Database.open()` set the pragmas after something had begun
a transaction, the app would run happily in `delete` mode, and the failure would appear in
sprint 15 as a background query blocking the UI's insert - with nothing pointing back here.

### How the code avoids it

`Database.open()` sets the pragmas on a **fresh** connection, which is in autocommit mode
with no transaction started. The migrator only calls `setAutoCommit(false)` *after*
`open()` has returned. And `DatabaseTest.walModeIsEnabled` checks the result - it asks
`PRAGMA journal_mode` and expects `wal` - which is the only reliable check, given that
setting it can silently fail.

WAL mode is stored **in the file**, so once any connection has switched it successfully,
every later connection finds it already set.

## 3. A migration file contains three statements. You pass the whole file to one `executeUpdate`. What actually runs?

**`SPEC.md` says only the first. With this project's driver, `Statement.executeUpdate`
actually runs all three - but the other two ways of running SQL really do run only the
first, silently.** It depends on which method you call.

Checked against `sqlite-jdbc` 3.46.1.3, with an in-memory database and this string:

```sql
CREATE TABLE a(x); CREATE TABLE b(y); CREATE TABLE c(z);
```

| Called as | Tables created |
|---|---|
| `statement.executeUpdate(sql)` | `a`, `b`, `c` - **all three** |
| `statement.execute(sql)` | `a` only |
| `connection.prepareStatement(sql).executeUpdate()` | `a` only |

None of the three threw an exception.

### Why they differ

SQLite's C library offers two ways to run SQL:

- **`sqlite3_prepare`** compiles **one** statement and returns a pointer to the unread
  rest of the string (the "tail"). The caller is expected to loop. `execute` and
  `PreparedStatement` in this driver compile once and never look at the tail - so
  statements two and three are dropped, with no error.
- **`sqlite3_exec`** is a convenience function that loops over every statement for you.
  This driver's `Statement.executeUpdate(String)` happens to use it.

That is an **implementation detail of one driver**. It is not promised by JDBC, not
documented as a feature, and not true of the other two methods on the very same
`Statement` object.

### Why the migrator splits anyway

- **It would be relying on an accident.** Change `executeUpdate` to `execute` in a later
  tidy-up - they look interchangeable - and V1 would create `expenses` and silently skip
  `budgets` and `schema_version`. The `INSERT INTO schema_version` would then fail, but
  `expenses` would already exist, and the error would point at the wrong thing.
- **Other databases behave differently again.** Postgres' driver runs several statements
  in one string; MySQL's refuses unless a connection option is set. Code that assumes one
  behaviour breaks when the driver changes.
- **It is visible.** A list of statements run one at a time is obvious to a reader, and a
  failure names the statement that failed.

So the answer to "what runs?" is: **it depends on the method and the driver, and when the
answer is "it depends", do not rely on it.** Split the file and run each statement on its
own, which works the same everywhere.

### The nasty part, which `SPEC.md` is right about

When only the first statement runs, **nothing tells you**. The first table is created, the
call returns normally, and the missing tables only surface later as `no such table:
budgets`, in code that has nothing to do with migrations.

## 4. Version 2 is recorded *after* the statements run, inside the same transaction. What would break if it were recorded in a separate transaction afterwards?

**A crash between the two would leave the database changed but still claiming to be at
version 1. The next start would try to apply V2 again - and fail, every time, for ever.**

Suppose the version row were written separately:

```
transaction 1:   ALTER TABLE expenses ADD COLUMN note TEXT;
                 CREATE INDEX idx_expenses_spent_on ...;
                 CREATE INDEX idx_expenses_category ...;
                 COMMIT                                      <- schema is now at v2

                 ~~~ the app is killed here: power cut, force quit, a crash ~~~

transaction 2:   INSERT INTO schema_version VALUES (2, ...)  <- never happens
```

Now the file has the `note` column and both indexes, but `schema_version` says `1`.

On the next start:

1. `currentVersion()` returns `1`.
2. `migrate()` decides V2 is needed and runs it.
3. `ALTER TABLE expenses ADD COLUMN note TEXT` fails: `duplicate column name: note`.
4. The migration throws, the app refuses to start.
5. Next start: exactly the same. And the next.

The database is **stuck**: it cannot move forward, because V2 cannot run twice, and it
cannot be recognised as finished, because it never said so. The only way out is someone
editing the file by hand. This is the worst possible state for a schema to be in - it does
not match what it claims to be.

If a migration contained only idempotent statements (`CREATE INDEX IF NOT EXISTS`), the
same crash would go unnoticed - re-running would quietly succeed. That sounds better and is
not: it only works for migrations that happen to be safe to repeat, and hides the problem
until the one that is not.

### With both in one transaction

```
BEGIN
    ALTER TABLE ...;  CREATE INDEX ...;  CREATE INDEX ...;
    INSERT INTO schema_version VALUES (2, ...);
COMMIT
```

A transaction is **atomic**: all of it is saved, or none of it. Kill the app anywhere before
`COMMIT` and SQLite rolls the whole thing back on the next open - the file is at version 1
with no `note` column, which is true, and V2 runs cleanly. Kill it after `COMMIT` and the
file is at version 2 and says so. There is no moment in which the two disagree.

(SQLite can do this for `ALTER TABLE` and `CREATE INDEX` because, unlike some databases,
it runs schema changes inside transactions like any other change. MySQL, for example,
commits automatically before most schema changes, so this guarantee is not available
there - one reason tools like Flyway record a failed migration as "failed" and ask for
manual repair.)

**A smaller failure in the other order.** If the version were written in a separate
transaction *first*, a crash afterwards would leave the file claiming version 2 with none of
V2's changes - and the migrator would never apply them, because it believes they are done.
The app would start, and fail later with `no such column: note`. Either way round, two
transactions means two facts that can disagree.
