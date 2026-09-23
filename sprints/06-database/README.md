# Sprint 06 · The database and its migrations

**Time:** ~2.5 hours · **Prerequisites:** 05 · **Produces:** `Database`, `SchemaMigrator`, `StoreException`, two SQL files

---

## Purpose

The first sprint that touches anything outside the JVM. Nothing stores an expense yet —
this sprint only gets you a **file that exists, has the right tables in it, and knows
which version of the schema it is at**.

That is worth a sprint on its own because three separate mechanics show up at once, and
all three are things you will keep meeting for the rest of your career: a resource that
must be closed, a checked exception that must be handled, and a schema that must
evolve without destroying what is already there.

## New concepts

### 1. `Connection` is a resource, and resources leak

A JDBC `Connection` holds a file handle. Lose the reference without closing it and the
handle stays open until the garbage collector happens to run a finaliser — which may be
never. The specification's warning is exact: *"A leaked connection is invisible until
the application hangs — and in a desktop app that hang is a frozen window."*

Java's answer is **try-with-resources**:

```java
try (Connection c = db.open();
     PreparedStatement ps = c.prepareStatement(SQL)) {
    ...
}   // ps.close() then c.close(), in reverse order, even if the body threw
```

Anything declared in the parentheses must implement `AutoCloseable`, and the compiler
generates the `finally` block that closes it. Reverse order matters: the statement is
closed before the connection it came from.

There is no situation in this project where you write `connection.close()` by hand. If
you find yourself doing it, the structure is wrong.

### 2. Checked exceptions, and why you wrap them

`SQLException` is **checked** — the compiler forces every caller to either catch it or
declare `throws SQLException`. Let that propagate and the declaration spreads: the
store throws it, so the service must declare it, so the UI must declare it, and now
your button handler has a `throws SQLException` on it. The database has leaked into
every layer through the *type system*, even though no file outside `store` imports
`java.sql`.

So the store catches it at its own boundary and rethrows something of its own:

```java
} catch (SQLException e) {
    throw new StoreException("failed to open " + path, e);
}
```

`StoreException` is unchecked, so it propagates silently up to sprint 15's
`Task.setOnFailed` without any signature mentioning it. **Passing `e` as the cause is
not optional** — drop it and you lose the SQLite error code, which is the only thing
that tells you whether the problem was a locked file, a missing directory or a
constraint violation.

### 3. A migration is a numbered, one-way step

The naive approach is `CREATE TABLE IF NOT EXISTS` on every startup. It works until the
day you need to *change* a table, at which point there is no way to know whether a given
database file has already been changed.

A `schema_version` table fixes that. Startup reads the highest applied version, runs
every numbered migration above it in order, and records each one. Version 0 (a brand
new file) runs everything; version 2 runs nothing. This is what Flyway and Liquibase do,
and doing it by hand once is the point.

**Migrations run before the window is shown.** A half-migrated database reaching an
interactive UI is much worse than an application that refuses to start.

### 4. Classpath resources are not files

The SQL lives in `src/main/resources/db/`. It is tempting to read it with
`new File("src/main/resources/db/V1__create_tables.sql")`. That works from your IDE and
breaks the moment you package the app in sprint 20, because by then the file is *inside
a jar* and there is no such path on disk.

```java
try (InputStream in = getClass().getResourceAsStream("/db/V1__create_tables.sql")) { ... }
```

The leading `/` means "from the root of the classpath". This works identically in the
IDE, under Maven, and inside a packaged bundle.

One consequence people trip over: **you cannot reliably list a classpath directory.** So
do not scan `/db/` for `V*.sql` files — hardcode the ordered list in the migrator. That
sounds like a step backwards and is not: the order migrations run in is a critical
correctness property, and having it written down explicitly is better than having it
emerge from a directory listing.

## The decisions you are implementing

### Where the database file lives

Three candidates, in order:

1. `-Dexpenses.db=<path>` — a system property. Tests use this; so does sprint 20's
   packaged build.
2. `~/Library/Application Support/ExpenseTracker/expenses.db` — the macOS convention.
3. `./data/expenses.db` — the development fallback.

The status bar in sprint 13 shows the resolved **absolute** path, which turns "which
database am I actually looking at?" from a debugging session into a glance.

### What V2 does

`V2__add_indexes.sql` creates the two indexes **and** adds a `note` column. The
`ALTER TABLE` is the important half: unlike `CREATE INDEX IF NOT EXISTS`, it is *not*
idempotent, so running it twice fails. That is what makes spec test 11 — "running
migrations twice applies nothing the second time" — a real test rather than one that
would pass even with a completely broken migrator.

## What you build

- `StoreException`
- `Database` — path resolution, `open()`, pragmas
- `SchemaMigrator` — version check, ordered application, transaction per migration
- `V1__create_tables.sql`, `V2__add_indexes.sql`

## Definition of done

- [ ] Running the migrator against an empty directory creates the file and reports
      version 2
- [ ] Running it again applies nothing (spec test 11)
- [ ] Every `SQLException` is wrapped in a `StoreException` with the cause attached
- [ ] The `onlyStoreImportsJavaSql` guard test still passes
- [ ] No `Connection` is closed by hand anywhere

---

## Reading check

1. Two resources are declared in one try-with-resources. The body throws, and closing
   the *first* resource also throws. Which exception does the caller see, and what
   happens to the other one?
2. Why does `PRAGMA journal_mode=WAL` have to run outside a transaction?
3. A migration file contains three statements. You pass the whole file to one
   `executeUpdate`. What actually runs?
4. Version 2 is recorded in `schema_version` *after* the statements run, inside the same
   transaction. What would break if it were recorded in a separate transaction
   afterwards?
