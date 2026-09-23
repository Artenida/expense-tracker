# ExpenseTracker — Build Plan (from Spec v3.0)

Working notes for building the JavaFX desktop app described in
*ExpenseTracker — Specification v3.0*. Covers environment setup, corrections to the
spec, and project layout.

> **For the day-to-day build order, use [SPRINTS.md](SPRINTS.md).** It breaks the six
> milestones below into twenty small sprints sized for reading and understanding the
> code, each with its own folder under `sprints/`. This file remains the reference for
> *why* — the environment, the corrections to the specification, and the architecture.

---

## 0. Verdict on the spec

The spec is good. The layering (`domain` → `store` → `service` → `ui`), the
integer-cents money rule, the "no JDBC on the FX thread" rule and the test list are
all sound and worth following literally.

There are **14 gaps or contradictions** that will cost time if you hit them mid-build.
They are listed in §2 with the decision to make for each. Resolve them *before* M1;
most of them are one-line decisions, but three (ID generation, migration split, import
transaction) change the shape of code you would otherwise have to rewrite.

---

## 1. Environment

Machine: macOS (Intel, x86_64). Nothing Java-related installed yet.

### 1.1 Install

```sh
brew install --cask temurin@21     # JDK 21 (LTS) — Java 21 is what the spec targets
brew install maven                 # 3.9.x
```

Verify:

```sh
/usr/libexec/java_home -V          # should list 21.x
java -version                      # openjdk 21.x
mvn -version                       # should report Java 21, not 17 or 8
```

If `java -version` reports something other than 21, put this in `~/.zshrc`:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
```

**Do not install a separate JavaFX SDK.** JavaFX comes in as Maven dependencies.
Downloading the SDK from gluonhq and also declaring Maven deps is the most common way
to end up with two JavaFX versions on the module path and a confusing crash.

### 1.2 Optional but worth it

- **SQLite CLI** — `brew install sqlite`. Lets you open `expenses.db` and check what
  your code actually wrote. Invaluable in M2.
- **IntelliJ IDEA Community** — free, best-in-class Maven + JavaFX support. VS Code's
  Java extension pack works but JavaFX run configs are fiddlier.
- **jenv** — only if you end up juggling multiple JDKs. Skip for now.

### 1.3 Architecture note (matters at M5)

You are on **Intel** macOS, so JavaFX resolves the `mac` classifier. The build output
will not run on an Apple Silicon Mac (which needs `mac-aarch64`) unless you add
explicit classifiers. Fine for now — noted so it is not a surprise when you hand the
app to someone.

---

## 2. Issues in the spec, and the decision for each

### 2.1 Only 6 of the "ten statements" are listed — UPDATE and DELETE are missing

§5 says ten statements cover the application and shows six. §8 requires edit and
delete. You also need to read budgets and read/write `schema_version`.

**Write these too:**

```sql
UPDATE expenses SET amount_cents = ?, category = ?, description = ?, spent_on = ?
WHERE id = ?;

DELETE FROM expenses WHERE id = ?;

SELECT category, limit_cents, updated_at FROM budgets;
SELECT category, limit_cents, updated_at FROM budgets WHERE category = ?;

SELECT COALESCE(MAX(version), 0) FROM schema_version;
INSERT INTO schema_version(version, applied_at) VALUES (?, ?);
```

Both `UPDATE` and `DELETE` must check `executeUpdate()`'s return value and throw
`ExpenseNotFoundException` when it is 0 — that is exactly what §8.3's "done when"
is asking for.

### 2.2 `exp-0001` IDs are broken as a sort key, and have no home

Two problems:

1. The filtered query orders by `spent_on DESC, id DESC`. `id` is `TEXT`, so the
   tiebreaker is lexical: `exp-9999` sorts *after* `exp-10000`. The ordering silently
   goes wrong at the 10,000th expense — and the 50,000-row fixture in M4 sails past it.
2. Generating "next sequential ID" means reading `MAX(id)` first, which is a query, a
   race, and cannot live in `domain` (which imports nothing).

**Decision — pick one:**

| Option | ID | Trade-off |
|---|---|---|
| **A (recommended)** | `UUID.randomUUID().toString()` | No counter, no race, no sort bug. Ugly in the debugger. |
| B | `exp-000001` zero-padded to a fixed width, generated in the store inside the insert transaction | Readable; still breaks past the padding width; needs a transaction to be safe |

Go with **A**. The ID is opaque plumbing; nothing in the UI shows it. If you want a
readable tiebreaker, add `created_at` to the `ORDER BY` instead:
`ORDER BY spent_on DESC, created_at DESC, id DESC`.

### 2.3 `created_at` exists in the schema but not in the domain

`Expense` is `(id, amount, category, description, date)` — no `created_at`, but the
column is `NOT NULL`. Your mapper cannot round-trip it.

**Decision:** add `createdAt` (an `Instant`) as a sixth field on `Expense`, set by a
static factory `Expense.newExpense(...)` which stamps `Instant.now()`, and by
`Expense.of(...)` (full constructor) when rehydrating from the database. This also
gives you a stable tiebreaker for §2.2.

### 2.4 The migration split contradicts §4

§4 shows one DDL block containing tables *and* indexes. §11 names the files
`V1__create_tables.sql` and `V2__add_indexes.sql`. If V1 is the §4 block verbatim,
V2 has nothing left to do and test 11 is vacuous.

**Decision:**

- `V1__create_tables.sql` — the three `CREATE TABLE` statements only.
- `V2__add_indexes.sql` — the two `CREATE INDEX` statements **plus**
  `ALTER TABLE expenses ADD COLUMN note TEXT;`. The `ALTER TABLE` is what makes the
  migration mechanism non-trivial: it is not idempotent, so it proves your version
  check actually works. (`CREATE INDEX IF NOT EXISTS` would pass even with a broken
  migrator.)

Also: `schema_version` has no uniqueness constraint. Add `version INTEGER PRIMARY KEY`
so applying the same migration twice fails loudly rather than inserting a duplicate row.

### 2.5 Migration files: two traps

1. **Do not read them as `File`s.** Use `getClass().getResourceAsStream("/db/V1__...")`.
   A `File` path works from your IDE and breaks the moment you `jpackage` in M5.
2. **You cannot list a classpath directory portably.** Do not scan `/db/` for `V*.sql`.
   Hardcode the ordered list in `SchemaMigrator`:
   `List.of("V1__create_tables.sql", "V2__add_indexes.sql")`.
3. **Split each file on `;` yourself.** Do not assume one `executeUpdate` runs a
   multi-statement file — SQLite compiles one statement at a time.

### 2.6 Percentage rounding flips budget status

R-2 says WARNING starts at exactly 80%. If you compute
`percentUsed = spent.divide(limit, 2, HALF_UP).movePointRight(2)`, then 79.996%
rounds to 80.00 and a budget that is *not* at the threshold reports WARNING. Test 9
("79.99% is OK") will pass and the real bug will hide behind it.

**Decision:** decide the status by cross-multiplication on the integer cents, never on
the rounded percentage:

```java
// WARNING iff spentCents * 100 >= limitCents * 80
// EXCEEDED iff spentCents >= limitCents
```

`percentUsed` stays a display-only value, rounded however you like.

### 2.7 `average` and `largest` on an empty month

Test 10 requires a summary of zeroes rather than an exception, but:

- `totalSpent.divide(BigDecimal.valueOf(entryCount))` throws `ArithmeticException`
  when `entryCount == 0`.
- `largest` has no value at all for an empty month, and the Definition of Done says
  no public method returns `null`.

**Decision:** guard the division (`entryCount == 0 → BigDecimal.ZERO.setScale(2)`),
and declare the record component as `Optional<Expense> largest`. Also define
`totalBudgeted` explicitly: **the sum of every configured budget limit**, not only the
categories that were spent in. Write that down in the README or the two summary
implementations will disagree.

### 2.8 SQLite + a multi-threaded executor = `SQLITE_BUSY`

U-1 tells you to own an `ExecutorService`; §7 shows a connection opened per call. With
two or more pool threads, two writes against the same file collide and the driver
throws `SQLITE_BUSY` — intermittently, which is the worst kind.

**Decision:**

- Use `Executors.newSingleThreadExecutor()` with a **daemon** thread factory. One
  background thread serialises all database access, makes task ordering predictable,
  and still satisfies "never block the FX thread". Daemon threads mean a forgotten
  `shutdown()` cannot hang the JVM (you still call `shutdown()` in `stop()` — belt
  and braces).
- Set pragmas on every connection in the factory:
  `PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000; PRAGMA foreign_keys=ON;`

### 2.9 Stale results when the filter changes quickly

Not mentioned in the spec, and it *will* happen. Change the month combo twice in
quick succession: task A (slow) and task B (fast) both run, B's `setOnSucceeded` lands
first, then A's overwrites the table with the *older* month's rows.

**Decision:** keep a `long requestId` counter in `MainView`. Stamp each reload, and in
`setOnSucceeded` ignore the result if the stamp is not the latest. Three lines, saves
an evening of confusion.

### 2.10 The import transaction does not fit the store API

"Import runs in a single transaction" and "cancel mid-run leaves the database
unchanged" cannot be done by calling `store.add(expense)` in a loop — each call opens
and closes its own connection and commits.

**Decision:** give `ExpenseStore` a dedicated method:

```java
int addAll(List<Expense> expenses, IntConsumer onProgress, BooleanSupplier cancelled);
```

It opens one connection, `setAutoCommit(false)`, batches the inserts, polls
`cancelled` between rows, and rolls back on cancellation or on any failure. The FX
`Task` passes `this::isCancelled` in and `updateProgress` out. JDBC will not respond
to `Thread.interrupt()`, so polling is the only mechanism that works.

### 2.11 The CSV format is undefined

"Export can be re-imported with no loss" is untestable without a format. Descriptions
are free text up to 100 chars and will contain commas.

**Decision:** RFC 4180, written by hand (no dependency — it is ~40 lines each way):

```
date,category,description,amount
2026-09-15,GROCERIES,"Weekly shop, incl. wine",24.90
```

- Header row required; validated on import.
- `"` quoting whenever the field contains `,`, `"` or a newline; `""` escapes a quote.
- Date ISO, amount plain decimal with `.`, category the **enum name** (not display name).
- Do not export the `id` — a re-import creates new expenses. If you want round-trip
  *identity*, export the id and make import an upsert; decide now and say so in the README.

### 2.12 The `javafx-fxml` dependency is not needed

§2 tells you to build layouts in Java code, then §3 lists `javafx-fxml`. Drop it. Add
it back in the follow-on project when you actually use FXML.

### 2.13 `SELECT *` fights "mapping lives in one place"

`SELECT *` means your mapper depends on column *order* staying stable — and you are
about to `ALTER TABLE ADD COLUMN note` in V2. List columns explicitly in every query
and read them by name in the mapper.

### 2.14 Two database locations are specified

§11 says `data/expenses.db` (git-ignored); M5 says a user-data directory. Both, in a
resolution order, decided once in `Database`:

1. `-Dexpenses.db=<path>` system property (tests and M6 use this)
2. `$XDG_DATA_HOME`-style user dir — on macOS
   `~/Library/Application Support/ExpenseTracker/expenses.db`
3. `./data/expenses.db` fallback for development

Create parent directories if missing. The status bar shows the resolved absolute path,
which makes "which database am I actually looking at?" a non-question.

### Smaller notes

- **`? IS NULL OR category = ?`** — you must bind the category value **twice**
  (params 3 and 4), or `setNull(3, Types.VARCHAR)` twice for "All". Easy to get wrong;
  write the test.
- **`movePointRight(2).longValueExact()`** throws `ArithmeticException` on a
  three-decimal amount. That is the validation working — catch it and convert to a
  `ValidationException`, do not let it escape.
- **Effort estimate (9–12 evenings)** is optimistic for a first JavaFX project. M4
  alone is realistically 5–6 if you have not used JavaFX before. Not a problem — just
  do not treat the estimate as a schedule.
- **TestFX on macOS** is fiddly. Tag the smoke tests `@Tag("ui")` and exclude them
  from the default `mvn test` run so a toolkit problem never blocks `mvn clean test`.

---

## 3. Database

**SQLite via `org.xerial:sqlite-jdbc`.** The spec's reasoning is right: it is a single
file, needs no server, and is genuinely the correct production choice for a
single-user desktop app.

Design decisions that stay:

| Rule | Why |
|---|---|
| Money as `INTEGER` cents | `REAL` silently corrupts money. `24.90` → `2490`. |
| Dates as ISO `TEXT` | SQLite has no date type. `LocalDate.parse` on the way out. |
| Category as `TEXT` enum name | `Category.valueOf` on read, with the failure handled. |
| `CHECK` constraints | Last line of defence; independent of your Java being correct. |
| `schema_version` table | Migrations in order, applied once. |

Additions from §2: `schema_version(version INTEGER PRIMARY KEY)`, `note TEXT` added in
V2, WAL + `busy_timeout` pragmas.

Postgres (M6, optional) stays behind `ExpenseStore` / `BudgetStore`. If the layering is
right, M6 is `PgExpenseStore` + a connection string. Note the SQL is not 100% portable:
`ON CONFLICT ... DO UPDATE` is fine in both, but SQLite's `TEXT` dates become `DATE`
and cents become `BIGINT` in Postgres.

---

## 4. Project structure

```
Expense_Tracker/
├─ pom.xml
├─ README.md
├─ PLAN.md                      ← this file
├─ .gitignore                   (target/, data/, *.db)
├─ data/expenses.db             (dev database, git-ignored)
└─ src/
   ├─ main/java/com/expensetracker/
   │  ├─ Launcher.java          main(); does NOT extend Application   ← see §6, M5
   │  ├─ App.java               extends Application; start() wires, stop() shuts down
   │  ├─ domain/                Expense, Budget, Category, BudgetStatus,
   │  │                         CategoryTotal, MonthSummary, ExpenseFilter,
   │  │                         Money, Validation, ValidationException
   │  ├─ store/                 ExpenseStore, BudgetStore       (interfaces)
   │  │                         JdbcExpenseStore, JdbcBudgetStore
   │  │                         Database, SchemaMigrator, StoreException, Sql
   │  ├─ service/               ExpenseService, BudgetService,
   │  │                         SummaryService (SQL + stream impls),
   │  │                         ImportService, ExpenseNotFoundException
   │  ├─ ui/                    MainView, ExpenseTableView, ExpenseDialog,
   │  │                         BudgetPane, SummaryPane, TopExpensesView, ErrorDialogs
   │  ├─ ui/model/              ExpenseRow, SummaryViewModel
   │  ├─ ui/task/               BackgroundRunner
   │  └─ io/                    CsvReader, CsvWriter, CsvFormatException
   ├─ main/resources/
   │  ├─ db/V1__create_tables.sql, V2__add_indexes.sql
   │  └─ app.css
   └─ test/java/...             mirrors the same packages
```

**Dependency direction — this is the whole design:**

```
ui  ──→  service  ──→  domain
                 └──→  store (interfaces)  ──→  domain
                                            └─→  java.sql
```

- `domain` imports nothing of yours, nothing from `java.sql`, nothing from `javafx.*`.
- `store` is the only package that imports `java.sql`.
- `ui` is the only package that imports `javafx.*`.
- `App`/`Launcher` are the only classes that know every layer. Keep them thin.

Enforce it cheaply — a plain test is enough, no ArchUnit needed:

```java
@Test void nothingOutsideStoreImportsJavaSql() throws IOException {
    try (var paths = Files.walk(Path.of("src/main/java"))) {
        var offenders = paths.filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.toString().contains("/store/"))
            .filter(p -> readString(p).contains("import java.sql"))
            .toList();
        assertThat(offenders).isEmpty();
    }
}
```

Write the same one for `javafx.` outside `ui/`. These two tests are the cheapest
architectural insurance in the project.

---

## 5. How "frontend" and "backend" fit together

There is no network here — it is one JVM process. But the split is real, and the point
of the project is that the seam is clean enough that M6 (swap SQLite for Postgres) and
the follow-on project (put the services behind a REST API) touch nothing above it.

```
  ┌──────────────── FX Application Thread ────────────────┐
  │  MainView / ExpenseDialog / SummaryPane / BudgetPane  │
  │        ▲                                   │          │
  │        │ setOnSucceeded                    │ reload() │
  └────────┼───────────────────────────────────┼──────────┘
           │                                   ▼
  ┌────────┴───────────── BackgroundRunner (1 daemon thread) ────────┐
  │   Task<T>.call()  →  ExpenseService / SummaryService / Budget…   │
  │                            │                                     │
  │                            ▼                                     │
  │              ExpenseStore / BudgetStore  (interface)             │
  │                            │                                     │
  │                            ▼                                     │
  │              JdbcExpenseStore  →  JDBC  →  expenses.db           │
  └──────────────────────────────────────────────────────────────────┘
```

**The contract, in four rules:**

1. **Every service call crosses the thread boundary.** The UI never calls a service
   directly — it calls `BackgroundRunner.run(supplier, onSuccess, onError)`, which
   builds the `Task`, submits it, and routes `setOnSucceeded` / `setOnFailed`.
   Centralising it in one class means there is exactly one place where you can get
   the threading wrong.

2. **Only domain objects cross.** `List<Expense>`, `MonthSummary`, `Optional<Budget>`.
   Never a `ResultSet`, never a `Connection`, never a `javafx.*` type going down.

3. **The UI holds no business logic.** `SummaryPane` receives a `MonthSummary` and
   formats it. If you find yourself dividing `spent` by `limit` in a view class, that
   belongs in `SummaryService`.

4. **Refresh is one method.** `MainView.reload()` re-runs the current filter *and*
   rebuilds the summary, and every mutation calls it on success. Never patch the
   `ObservableList` by hand after an insert — it drifts out of sync with the database
   and diagnosing that costs an evening.

**The adapter layer.** `Expense` is an immutable domain object with no observable
properties. `ui/model/ExpenseRow` wraps one and exposes `StringProperty date`,
`StringProperty category`, `StringProperty description`, `StringProperty amount`
(pre-formatted to two decimals) plus `Expense source()` for when the dialog needs the
real thing. This one small class is what keeps `javafx.*` out of the domain.

**TableView sorting gotcha.** Back the table with
`new SortedList<>(observableList)` and call
`sorted.comparatorProperty().bind(table.comparatorProperty())`. If you set the plain
`ObservableList` as the table's items, every `setAll()` in `reload()` silently drops
the user's chosen sort order.

---

## 6. Implementation order

> Superseded by [SPRINTS.md](SPRINTS.md), which splits these six milestones into twenty
> sprints. Kept here because it maps this plan onto the specification's own milestone
> numbering, which the PDF refers to throughout.

Follow the spec's discipline: **no database connection until M1 is green, no window
until M3 is green.** The pull towards building the window first is strong precisely
because it is the visible part — and it is what produces apps with SQL in button
handlers.

### M0 — Scaffolding (½ evening)

- `git init`, `.gitignore` (`target/`, `data/`, `*.db`, `.idea/`, `.DS_Store`).
- Delete the stray `main.js` in the project root.
- `pom.xml`: Java 21 release, `javafx-controls` 21.0.4, `sqlite-jdbc` 3.46.1.3,
  `junit-jupiter` 5.11.x, `javafx-maven-plugin` 0.0.8 with
  `<mainClass>com.expensetracker.Launcher</mainClass>`, `maven-surefire-plugin`
  configured to exclude `@Tag("ui")`.
- **Gate:** `mvn clean test` is green with zero tests. Get this working before writing
  any code — a broken pom is much easier to debug on its own.

### M1 — Domain (1–2 evenings) · *no database*

Build: `Money` (the single cents↔BigDecimal conversion point), `Validation`
(non-throwing, returns `List<String>`), `Expense`, `Budget`, `Category`,
`BudgetStatus`, `CategoryTotal`, `MonthSummary`, `ExpenseFilter`.

- `Money.toCents` / `Money.fromCents` — the *only* place the conversion exists.
- `Validation.validateAmount(String)` returns messages; `Expense`'s constructor calls
  it and throws `ValidationException` when non-empty. This is how U-2 is honest rather
  than duplicated: the dialog reports, the domain throws, one implementation.
- `BudgetStatus.of(long spentCents, long limitCents)` using the cross-multiplication
  from §2.6.
- `equals`/`hashCode` on `Expense` compare amounts with `compareTo() == 0`, not
  `equals` — `24.9` and `24.90` are the same money, different scale.

**Gate:** tests 1, 2, 3, 9 pass. An invalid `Expense` cannot be constructed. No class
in `domain` imports `java.sql` or `javafx.*` — the guard tests from §4 pass.

### M2 — Database (2–3 evenings)

Build: `Database` (path resolution from §2.14, pragmas from §2.8), `SchemaMigrator`,
the `Sql` constants class, `ExpenseStore`/`BudgetStore` interfaces,
`JdbcExpenseStore`/`JdbcBudgetStore`, `StoreException`.

Order: migrations first (nothing else works without a schema), then insert + findById
(proves the mapper both ways), then the filtered list, then update/delete, then
budgets upsert, then the aggregate queries.

- One private `map(ResultSet)` method, reading columns by name.
- try-with-resources on every `Connection`, `PreparedStatement`, `ResultSet`.
- Every `SQLException` caught in `store` and rethrown as `StoreException` with the cause.
- Tests use `@TempDir` — a fresh database file per test, never a shared one, never
  your real data.

**Gate:** tests 4, 5, 7, 11 pass. Data survives a restart. The `'; DROP TABLE
expenses; --` description is stored as text and the table still exists.

### M3 — Services (2 evenings) · *still no window*

Build: `ExpenseService`, `BudgetService`, `SummaryService`, `ImportService`,
`ExpenseNotFoundException`.

- `SummaryService` gets **both** summary implementations — `summaryViaSql(YearMonth)`
  using `GROUP BY`, and `summaryViaStream(YearMonth)` loading the month's rows and
  grouping with `Collectors.groupingBy`. One parameterised test runs both against the
  same fixture and asserts they are equal.
- Write the three README sentences on when each is right *now*, while the reasoning is
  fresh. (Short version: push aggregation into the database when the result is much
  smaller than the input and the database can use an index; keep it in Java when you
  need the rows anyway, when the logic does not express well in SQL, or when you want
  it testable without a database.)
- `ImportService.importCsv` does parse → validate all → `store.addAll(...)` in one
  transaction. Validate the whole file *before* opening the transaction so the error
  message can name the failing line before anything is written.

**Gate:** tests 6, 8, 10 pass. The two summaries agree. The R-2 boundaries (79.99 / 80
/ 100) are tested.

### M4 — Desktop UI (3–5 evenings)

Build in this order, checking each piece runs before moving on:

1. `Launcher` + `App` + an empty `BorderPane` + status bar. Migrations run in
   `init()`, **before** the stage is shown, with an error `Alert` and a clean exit if
   they fail. A half-migrated database reaching an interactive UI is worse than an app
   that refuses to start.
2. `BackgroundRunner` + `ExpenseRow` + the `TableView` with a `SortedList`. Get one
   hardcoded filter loading rows through a `Task`.
3. Month and category combos wired to `reload()`, with the request-stamp guard from §2.9.
4. `SummaryPane` + `app.css` with `.status-ok` / `.status-warning` / `.status-exceeded`
   / `.status-no-budget`. The `ProgressBar` clamps at 1.0 — an exceeded budget shows a
   full red bar, never an over-full one.
5. `ExpenseDialog` for add, then reuse it for edit (double-click). Save button bound to
   a validity `BooleanBinding`; per-field error labels, not one message at the bottom.
6. Delete: context menu + `Delete` key + confirmation `Alert` naming the expense.
7. `BudgetPane` — editable cell per category, upsert on commit.
8. Top-N view (`LIMIT` in SQL, not an in-memory sort).
9. CSV import/export via `FileChooser`, on a `Task`, with a modal `ProgressBar` dialog
   and a working Cancel.
10. `ErrorDialogs` — catch `StoreException` and `ValidationException` at the UI
    boundary, show a sentence a person can act on, log the full trace.

**Gate:** every capability in §8 works. Bad input never crashes the app. Seed 50,000
rows and confirm the window still responds while a query runs. Closing the window
exits the JVM cleanly.

### M5 — Packaging (1–2 evenings)

**The one trap that catches everyone:** `jpackage` and fat jars fail with *"JavaFX
runtime components are missing"* when the main class extends `Application` and JavaFX
is on the classpath rather than the module path. The fix is the `Launcher` class,
which is why it is in the structure from M0:

```java
public final class Launcher {
    public static void main(String[] args) { App.main(args); }
}
```

`Launcher` does **not** extend `Application`. Point `jpackage` and the jar manifest at
`Launcher`, not `App`.

Then: `jpackage` producing a `.app`/`.dmg`, an app icon, and the database landing in
`~/Library/Application Support/ExpenseTracker/` — not next to the executable, which
is read-only inside a bundle.

**Gate:** someone with no JDK installed can run it.

### M6 — Postgres swap (optional, 2 evenings)

Postgres in Docker, `PgExpenseStore`/`PgBudgetStore`, HikariCP, store chosen by
configuration. **Nothing in `service` or `ui` changes.** That is the entire point of
the exercise — if you have to touch either, the layering leaked and it is worth
finding out where.

---

## 7. Definition of done

Straight from §14, unchanged — these are good:

- [ ] `mvn clean test` green from a fresh clone, with no database file present
- [ ] `mvn javafx:run` starts the app from that same fresh clone
- [ ] The app creates and migrates its own database on first run, before the window appears
- [ ] Nothing outside `store` imports `java.sql` *(guard test)*
- [ ] Nothing outside `ui` imports `javafx.*` — in particular not `domain` *(guard test)*
- [ ] No SQL built by string concatenation with user input
- [ ] No public method returns `null`
- [ ] No service call on the FX Application Thread
- [ ] Closing the window exits the JVM cleanly, no lingering non-daemon threads
- [ ] You can explain every line without re-reading it

---

## 8. Decisions to make before you write a line

| # | Decision | Recommendation |
|---|---|---|
| 1 | Expense ID scheme | `UUID` (§2.2) |
| 2 | `createdAt` on the domain object | Yes, as `Instant` (§2.3) |
| 3 | What V2 migration does | Indexes + `note` column (§2.4) |
| 4 | `totalBudgeted` definition | Sum of all configured limits (§2.7) |
| 5 | Is `12,50` a valid amount? | **No** — `^\d{1,9}(\.\d{1,2})?$` only, and test it |
| 6 | CSV exports the id? | No; import always creates new rows (§2.11) |
| 7 | Executor size | Single daemon thread (§2.8) |

Record answers 5 and 6 in the README — they are the two a future reader will ask about.
