# ExpenseTracker

A desktop expense tracker in Java 21 and JavaFX: record expenses, set a monthly budget per
category, and see where the money went. It was built in twenty small sprints, which
`SPRINTS.md` lists; `PLAN.md` has the full design.

## Running it

| | |
|---|---|
| From source | `mvn javafx:run` |
| As a jar | `mvn clean package`, then `java -jar target/expense-tracker-1.0-SNAPSHOT.jar` |
| Tests | `mvn clean test`. Tests tagged `ui` and `slow` are excluded; run everything with `mvn test -DexcludedGroups=` |
| macOS app | `./scripts/package-mac.sh` builds `build/dist/ExpenseTracker-1.0.0.dmg`; `--app-image` builds just the `.app`, faster |

The `ui` tests render off-screen through Monocle, so they need no display and no macOS
Accessibility permission. Add `-Dtestfx.headless=false` to watch them in real windows.

The `.app` carries its own trimmed Java runtime (about 100 MB), so the person running it
needs no JDK. It is built for the architecture of the machine that built it, because the
bundled JavaFX native libraries are, and it is unsigned: the first open needs right-click,
then Open.

### Where the database lives

The status bar shows the path in use. It is chosen in this order:

1. `-Dexpenses.db=/some/file.db`, when set. The smoke tests use this to stay off real data.
2. `~/Library/Application Support/ExpenseTracker/expenses.db` on macOS, from source or from
   the bundle. A `.app` is read-only, so the database can never live next to the executable.
3. `./data/expenses.db` elsewhere.

The app creates the directory and runs the migrations itself, before the window appears.

### Architecture

```
ui  ──►  service  ──►  store (interfaces)  ──►  domain
 │          │                                      ▲
 └──────────┴──────────────────────────────────────┘
```

`domain` imports nothing of ours. Only `store` imports `java.sql`, and only `ui` imports
`javafx` (plus `App` and `Launcher`). `ui` uses services, never stores; its one import from
`store` is `StoreException`, which services throw. `ArchitectureTest` enforces all of this.
`App` is the one class that builds every layer.

`Launcher` exists only to call `App.main`. A main class that extends `Application` refuses to
start from the classpath ("JavaFX runtime components are missing"), which is where `java -jar`
and the bundle put JavaFX. `LauncherTest` stops it being merged away.

## Decisions a reader will ask about

### Identity and money

- **Ids are random UUIDs, made by the application**, not database row numbers. An expense has
  its id before it is saved, so nothing waits on an insert to learn it, and two imports can
  never collide.
- **Amounts are `BigDecimal` in Java and integer cents in the database**, converted only in
  `Money`. `Money.format` is the only place an amount is rounded for display.
- **`12,50` is rejected.** Amounts accept digits, optionally a `.` and up to two decimals,
  whatever the machine's locale, so the same file or keystrokes mean the same thing on every
  computer. `20` and `20.5` are fine.

### The month summary

- **`totalBudgeted` is the sum of every configured budget limit**, including categories with
  no spending that month. It is not only the categories that appear in the totals.
- **`average` is `totalSpent / entryCount`**, rounded half-up to two decimals, and `0.00` for an
  empty month.
- **A category appears in the totals if it was spent in or has a budget.** A category with
  neither is left out.
- **Ties are broken the same way everywhere.** Categories are ordered by spent descending, then
  by name. The largest expense is chosen by amount, then latest date, then highest id.

### CSV import and export

```
date,category,description,amount
2026-09-15,GROCERIES,"Weekly shop, incl. wine",24.90
```

The header is required and checked. Fields are quoted as RFC 4180 describes. Dates are ISO,
amounts are plain decimals with a `.`, categories are enum names, and the file is always UTF-8.

**The export has no `id` column, and import always adds new expenses.** An export records what
you spent, not the application's internal ids. Importing means "add these expenses", not
"restore this backup". One consequence follows: if you export a month and import that same
file, every expense in it appears twice. That is intended, and
`ImportServiceTest.exportingThenReimportingDuplicatesEveryExpense` checks it.

An import is all or nothing. The whole file is read and checked first, and any bad line is
reported with its line number (the header is line 1) before anything is written. The rows are
then inserted in a single transaction. The reader does not accept line breaks inside quoted
fields. Descriptions can never contain one, so the app's own exports are unaffected.

## Where the aggregation belongs

`SummaryService` computes the month summary two ways. `viaSql` uses `GROUP BY` and `LIMIT`.
`viaStream` loads the month's rows and uses `groupingBy` and `max`. A test asserts that the two
results are equal.

**Push the aggregation into the database** when the answer is much smaller than the input: the
rows are added up where they already are, and only the handful of category totals crosses into
Java. **Keep it in Java** when you need the individual rows anyway (the table in sprint 14 does),
when the logic is awkward to express in SQL, or when you want it tested without a database.
**Never split one calculation across both**, because a total that is half computed in SQL and
half in Java cannot be checked against either one.

### Measured

These timings come from 50,000 random expenses in one month, with a budget on every category.
Each figure is the median of five runs after warm-up, on a laptop SSD
(`SummaryServiceTest.compareBothImplementationsOnFiftyThousandRows`):

| | time |
|---|---|
| `viaSql` | 201 ms |
| `viaStream` | 371 ms |

SQL is about 1.8× faster. The main saving is that no `Expense` objects are built: the stream
version creates 50,000 of them, while the SQL version only reads 7 category rows and 1 expense.
The gap is smaller than expected because this fixture puts every row in the chosen month. The
index on `spent_on` has nothing to skip, and SQLite still reads all 50,000 rows twice, once for
the `GROUP BY` and once for the `ORDER BY ... LIMIT 1`. With several years of data and one month
selected, the index would skip most of the table.

To re-run the timing: `mvn test -Dgroups=slow -DexcludedGroups= -Dtest=SummaryServiceTest`
