# Sprint 06 · Build specification

---

## `src/main/resources/db/V1__create_tables.sql`

Tables only. No indexes — those are V2's job.

```sql
CREATE TABLE expenses (
    id           TEXT PRIMARY KEY,
    amount_cents INTEGER NOT NULL CHECK (amount_cents > 0),
    category     TEXT    NOT NULL,
    description  TEXT    NOT NULL,
    spent_on     TEXT    NOT NULL,
    created_at   TEXT    NOT NULL
);

CREATE TABLE budgets (
    category    TEXT PRIMARY KEY,
    limit_cents INTEGER NOT NULL CHECK (limit_cents > 0),
    updated_at  TEXT    NOT NULL
);

CREATE TABLE schema_version (
    version    INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL
);
```

Two changes from the original specification:

- **No `IF NOT EXISTS`.** The migrator guarantees V1 runs exactly once. `IF NOT EXISTS`
  would hide a broken version check instead of exposing it.
- **`version INTEGER PRIMARY KEY`** rather than a plain column. Applying the same
  migration twice now fails on a constraint rather than silently inserting a duplicate
  row and leaving `MAX(version)` looking fine.

## `src/main/resources/db/V2__add_indexes.sql`

```sql
ALTER TABLE expenses ADD COLUMN note TEXT;

CREATE INDEX idx_expenses_spent_on ON expenses(spent_on);
CREATE INDEX idx_expenses_category ON expenses(category);
```

`note` is unused by the application. It exists to make the migration mechanism
non-trivial — see README. SQLite's `ALTER TABLE ADD COLUMN` is cheap: it changes the
table header, not the rows.

---

## `store/StoreException.java`

```java
package com.expensetracker.store;

public class StoreException extends RuntimeException {
    public StoreException(String message, Throwable cause) { super(message, cause); }
    public StoreException(String message)                  { super(message); }
}
```

The message is written for **you**, reading a log — `"failed to load expense abc-123"`.
Sprint 18 shows the user something different and friendlier. Do not try to make one
string serve both audiences.

---

## `store/Database.java`

```java
public final class Database {

    private final Path path;

    public static Database resolveDefault();
    public Database(Path path);

    public Connection open();       // throws StoreException, not SQLException
    public Path path();
}
```

### `resolveDefault()`

```java
String override = System.getProperty("expenses.db");
if (override != null && !override.isBlank()) return new Database(Path.of(override));

String home = System.getProperty("user.home");
Path userData = Path.of(home, "Library", "Application Support", "ExpenseTracker");
if (Files.isDirectory(userData.getParent())) return new Database(userData.resolve("expenses.db"));

return new Database(Path.of("data", "expenses.db"));
```

The middle branch checks that `~/Library/Application Support` exists — it always does on
macOS and never on Linux, so the same code falls through to `./data` elsewhere without
a platform check.

### The constructor creates the parent directory

```java
public Database(Path path) {
    this.path = path.toAbsolutePath();
    try {
        Files.createDirectories(this.path.getParent());
    } catch (IOException e) {
        throw new StoreException("cannot create database directory " + this.path.getParent(), e);
    }
}
```

`toAbsolutePath()` matters for the status bar: a relative path tells the user nothing
about which file is open. `createDirectories` is a no-op when the directory exists, so
there is no need to check first.

### `open()`

```java
public Connection open() {
    try {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("PRAGMA busy_timeout = 5000");
            s.execute("PRAGMA foreign_keys = ON");
        }
        return c;
    } catch (SQLException e) {
        throw new StoreException("failed to open database at " + path, e);
    }
}
```

| Pragma | What it buys |
|---|---|
| `journal_mode = WAL` | Write-ahead logging. A reader is not blocked by a writer, which matters from sprint 15 when a query and an insert can overlap. Persists in the file — it only needs setting once, but setting it every time is harmless and self-documenting. |
| `busy_timeout = 5000` | When the file *is* locked, wait up to 5 s instead of failing immediately with `SQLITE_BUSY`. |
| `foreign_keys = ON` | No effect today (there are no foreign keys) — but it is off by default in SQLite, which surprises people, and turning it on here means it is already right the day you add one. |

**`journal_mode` cannot be set inside a transaction.** That is why the pragmas run here,
on a fresh connection, before anything begins one.

Note `open()` returns an **open** connection and the caller closes it. That is unusual —
normally a method that opens something should close it — and it is why every call site
must use try-with-resources. The method name is the contract.

> **No connection pool.** Every operation opens and closes a connection. For SQLite on
> a local file this costs microseconds, and it keeps the code readable. Sprint 20's
> optional Postgres swap is where HikariCP would come in.

---

## `store/SchemaMigrator.java`

```java
public final class SchemaMigrator {

    private static final List<String> MIGRATIONS = List.of(
        "V1__create_tables.sql",
        "V2__add_indexes.sql"
    );

    public static final int TARGET_VERSION = MIGRATIONS.size();

    private final Database database;

    public SchemaMigrator(Database database) { ... }

    public int migrate();          // returns the version now applied
    public int currentVersion();
}
```

### `currentVersion()`

Returns 0 when the database is brand new — when `schema_version` does not exist yet:

```java
try (Connection c = database.open();
     Statement s = c.createStatement();
     ResultSet rs = s.executeQuery(
         "SELECT name FROM sqlite_master WHERE type='table' AND name='schema_version'")) {
    if (!rs.next()) return 0;
}
// then SELECT COALESCE(MAX(version), 0) FROM schema_version
```

`sqlite_master` is SQLite's own catalogue of everything in the file. Querying it is how
you ask "does this table exist?" without relying on a failed query.

### `migrate()`

```
1. current = currentVersion()
2. for i from current+1 to MIGRATIONS.size():
     a. read MIGRATIONS[i-1] from the classpath
     b. split into statements
     c. open a connection, setAutoCommit(false)
     d. execute each statement
     e. INSERT INTO schema_version(version, applied_at) VALUES (i, now)
     f. commit   — on any exception, rollback and throw StoreException
3. return MIGRATIONS.size()
```

**Steps (d) and (e) are in the same transaction.** That is what makes the operation
atomic: either the statements ran *and* the version was recorded, or neither happened.
Record the version separately and a crash in between leaves a database whose schema
does not match what it claims — the worst possible state, because every subsequent run
believes the migration is done.

### Reading and splitting a migration file

```java
private String read(String name) {
    try (InputStream in = SchemaMigrator.class.getResourceAsStream("/db/" + name)) {
        if (in == null) throw new StoreException("migration not found on classpath: " + name);
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
        throw new StoreException("failed to read migration " + name, e);
    }
}

private List<String> statements(String sql) {
    return Arrays.stream(sql.split(";"))
                 .map(String::strip)
                 .filter(s -> !s.isEmpty())
                 .toList();
}
```

**You must split it yourself.** SQLite compiles one statement at a time, so handing a
three-statement file to a single `execute` runs only the first — silently, with no
error. That is the answer to reading-check question 3, and it is a genuinely nasty bug
because the first table gets created and the rest do not.

Splitting on `;` is naive: it would break on a semicolon inside a string literal or a
comment. The migration files here contain neither. That is a constraint on the files,
so write it as a comment in the method rather than leaving the next reader to discover
it.

### The transaction shape

```java
Connection c = database.open();
try {
    c.setAutoCommit(false);
    // ... statements, then the version insert ...
    c.commit();
} catch (SQLException e) {
    try { c.rollback(); } catch (SQLException ignored) { }
    throw new StoreException("migration " + name + " failed", e);
} finally {
    try { c.setAutoCommit(true); c.close(); } catch (SQLException ignored) { }
}
```

This is the one place in the project where a connection is **not** managed by
try-with-resources, because the rollback has to happen before the close. Read the shape
carefully — `commit` on success, `rollback` in the catch, restore autocommit and close
in the finally. You will write it once more, in sprint 09.
