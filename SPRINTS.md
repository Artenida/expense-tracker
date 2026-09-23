# ExpenseTracker — Sprint Plan

Twenty sprints, built to be **read**, not just shipped.

This document is the map. Each sprint has its own folder under `sprints/` holding the
brief, the build specification and the tests. Work one sprint at a time, in order.

---

## How this is designed

The ordinary way to build this app would be six milestones. That is the right shape if
your goal is a finished product. It is the wrong shape if your goal is to understand
the code, because milestone 4 alone would drop a `Task`, an `ObservableList`, a
`SortedList`, a `BooleanBinding`, a `Dialog` and a `FileChooser` on you at once, and
you would end up with working code you cannot explain.

So this plan splits the same work into twenty sprints under one constraint:

> **Each sprint introduces at most three new Java concepts, and produces something
> you can run or test.**

Some sprints are 45 minutes. The longest is an evening. That is deliberate — the
bottleneck is not typing, it is understanding what you typed.

## The loop for every sprint

Each sprint folder has three files, used in this order:

| File | When | What it is for |
|---|---|---|
| `README.md` | **Before** you write code | Purpose, the concepts in play, what to look for |
| `SPEC.md` | While you build | Exact classes, signatures and rules |
| `TESTS.md` | To finish | The tests, and what each one proves |

Then — and this is the part that does the actual teaching — answer the
**Reading check** at the bottom of `README.md` without looking at your code. If you
cannot, you typed rather than learned, and the fix is to re-read that sprint's files,
not to push on to the next one.

## Rules that hold across all twenty

These never change, and every sprint assumes them:

1. **Dependency direction is one-way.**
   `ui → service → domain`, and `service → store (interfaces) → domain`.
   `domain` imports nothing of yours.
2. **`java.sql` appears only in `store`.** Nowhere else, ever.
3. **`javafx.*` appears only in `ui`.** Especially not in `domain`.
4. **Money is `BigDecimal` in Java and integer cents in the database**, converted in
   exactly one class.
5. **No public method returns `null`.** Use `Optional`, or an empty collection.
6. **From sprint 15 onwards, no service call happens on the FX Application Thread.**

Sprints 01 and 04 install the guard tests that enforce rules 1–3 automatically.

---

## The sprints

### Phase 1 · Domain — no database, no window (sprints 01–05)

Pure Java. Every class here is testable in milliseconds with nothing running. This is
the easiest code in the project to read, which is why it comes first.

| # | Sprint | You build | New concepts | Time |
|---|---|---|---|---|
| 01 | [Skeleton](sprints/01-skeleton/) | `pom.xml`, package tree, one passing test | Maven layout, JUnit 5, packages | 1h |
| 02 | [Enums](sprints/02-enums/) | `Category`, `BudgetStatus` | Enums with fields, static factory, `switch` | 1h |
| 03 | [Money](sprints/03-money/) | `Money`, `Validation`, `ValidationException` | `BigDecimal`, scale, unchecked exceptions | 2h |
| 04 | [Core objects](sprints/04-core-objects/) | `Expense`, `Budget` | Encapsulation, immutability, `equals`/`hashCode` | 2h |
| 05 | [Records](sprints/05-records/) | `CategoryTotal`, `MonthSummary`, `ExpenseFilter` | Records, `Optional`, `YearMonth` | 1.5h |

### Phase 2 · Store — the database layer (sprints 06–09)

JDBC by hand. The first time you will see a resource that must be closed, a checked
exception that must be wrapped, and a transaction.

| # | Sprint | You build | New concepts | Time |
|---|---|---|---|---|
| 06 | [Database](sprints/06-database/) | `Database`, `SchemaMigrator`, `StoreException` | `Connection`, try-with-resources, classpath resources | 2.5h |
| 07 | [First store](sprints/07-first-store/) | `ExpenseStore` interface, insert + `findById`, the mapper | Interfaces, `PreparedStatement`, `ResultSet` | 2.5h |
| 08 | [Queries](sprints/08-queries/) | Filtered list, `update`, `delete`, `BudgetStore` | Parameter binding, `executeUpdate` row counts, upsert | 2.5h |
| 09 | [Transactions](sprints/09-transactions/) | `addAll`, rollback, aggregate query | `setAutoCommit`, `GROUP BY`, functional interfaces | 2h |

### Phase 3 · Service — the logic layer (sprints 10–12)

Thin classes that coordinate. The interesting part is what they *don't* do.

| # | Sprint | You build | New concepts | Time |
|---|---|---|---|---|
| 10 | [Services](sprints/10-services/) | `ExpenseService`, `BudgetService` | Layering, delegation, custom exceptions | 1.5h |
| 11 | [Summary twice](sprints/11-summary/) | `SummaryService` — SQL and stream versions | Streams, `Collectors.groupingBy`, `Comparator` | 3h |
| 12 | [CSV](sprints/12-csv/) | `CsvReader`, `CsvWriter`, `ImportService` | File I/O, parsing, `BufferedReader` | 2.5h |

### Phase 4 · UI — the desktop layer (sprints 13–19)

The hardest code to read in the project, so it gets the smallest sprints. Nothing here
touches the database directly.

| # | Sprint | You build | New concepts | Time |
|---|---|---|---|---|
| 13 | [A window](sprints/13-window/) | `Launcher`, `App`, `BorderPane`, status bar | `Application` lifecycle, `Stage`/`Scene`, layout | 2h |
| 14 | [The table](sprints/14-table/) | `ExpenseRow`, `ExpenseTableView` | `ObservableList`, properties, cell factories | 2.5h |
| 15 | [Background work](sprints/15-background/) | `BackgroundRunner` | Threads, `Task`, the FX thread rule | 3h |
| 16 | [Filters](sprints/16-summary-panel/) | ComboBoxes, `reload()`, `SummaryPane`, `app.css` | Listeners, bindings, CSS style classes | 3h |
| 17 | [The dialog](sprints/17-dialog/) | `ExpenseDialog` — add and edit | `Dialog`, `BooleanBinding`, live validation | 3h |
| 18 | [Mutations](sprints/18-mutations/) | Delete, `BudgetPane`, top-N view | Context menus, editable cells, `Alert` | 2.5h |
| 19 | [CSV in the UI](sprints/19-csv-ui/) | `FileChooser`, progress dialog, cancel | Modal dialogs, progress binding, cancellation | 2.5h |

### Phase 5 · Ship (sprint 20)

| # | Sprint | You build | New concepts | Time |
|---|---|---|---|---|
| 20 | [Packaging](sprints/20-packaging/) | `jpackage` bundle, icon, user-data directory | Build plugins, the `Launcher` trap | 2h |

**Total: roughly 45 hours.** Call it 15–20 evenings at a pace where you actually read
what you write. The original spec estimated 9–12; that estimate assumed you already
knew JavaFX.

---

## Dependency order

You cannot reorder these freely. The hard constraints:

```
01 ─→ 02 ─→ 03 ─→ 04 ─→ 05 ─┬─→ 06 ─→ 07 ─→ 08 ─→ 09 ─→ 10 ─→ 11 ─→ 12
                             │                                        │
                             └────────────────────────────────────────┴─→ 13 ─→ 14 ─→ 15 ─→ 16 ─→ 17 ─→ 18 ─→ 19 ─→ 20
```

The two that matter most:

- **Do not open a database connection before sprint 06.** The domain has to be right
  first, or you will be debugging two layers at once.
- **Do not open a window before sprint 13.** This is the one people break, because the
  window is the visible part. Breaking it is what produces applications with SQL in
  button handlers.

Sprints 18 and 19 can swap. Sprint 20 can happen any time after 13.

---

## Decisions already made

These came out of reviewing the original specification (see [PLAN.md](PLAN.md) §2 for
the reasoning). They are baked into the sprint specs — you do not need to re-decide
them, but you should understand why:

| Decision | Choice | Sprint |
|---|---|---|
| Root package | `com.expensetracker` | 01 |
| Expense ID | `UUID`, not `exp-0001` | 04 |
| `createdAt` on the domain object | Yes, an `Instant` | 04 |
| Budget status thresholds | Integer cross-multiplication, never a rounded percentage | 02 |
| `totalBudgeted` | Sum of **all** configured limits | 05 |
| Amount input format | `^\d{1,9}(\.\d{1,2})?$` — `12,50` is rejected | 03 |
| V2 migration | Indexes **and** an `ALTER TABLE ADD COLUMN note` | 06 |
| Executor | One daemon thread | 15 |
| CSV | RFC 4180, no `id` column | 12 |
| Database location | System property → user data dir → `./data` | 06 |

---

## Tracking

Tick these off as you go.

- [ ] 01 Skeleton
- [ ] 02 Enums
- [ ] 03 Money
- [ ] 04 Core objects
- [ ] 05 Records
- [ ] 06 Database
- [ ] 07 First store
- [ ] 08 Queries
- [ ] 09 Transactions
- [ ] 10 Services
- [ ] 11 Summary twice
- [ ] 12 CSV
- [ ] 13 A window
- [ ] 14 The table
- [ ] 15 Background work
- [ ] 16 Filters and summary
- [ ] 17 The dialog
- [ ] 18 Mutations
- [ ] 19 CSV in the UI
- [ ] 20 Packaging
