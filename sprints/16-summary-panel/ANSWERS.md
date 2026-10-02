# Sprint 16 · Reading check answers

Answers to the four questions at the end of `README.md`.

---

## 1. Setting the initial month in the constructor triggers `reload()` through the listener. What must already be constructed for that to work, and what happens if it is not?

### What `reload()` touches

Everything it reads or writes has to exist before the listener can fire:

- **`filterPanel`**, because `reload()` starts with `filterPanel.currentFilter()`.
- **`tableView` and `summaryPane`**, because the callbacks are `tableView::setRows` and
  `summaryPane::update`.
- **The two `BackgroundRunner.Latest` streams and the services**, because the work lambdas
  call `expenses.find` and `summaries.summarise`.

### The trap in the spec's own order

`SPEC.md` builds the table and the summary pane first, then
`filterPanel = new FilterPanel(this::reload)`. That covers the second point and not the
first. If `FilterPanel`'s constructor calls `month.setValue(now)` **after** adding its
listener, then `reload()` runs **inside** `new FilterPanel(...)`. That is before the
assignment to `filterPanel` completes, so the field is still `null`:

```
NullPointerException: Cannot invoke "FilterPanel.currentFilter()" because "this.filterPanel" is null
```

The services would not be ready either. They are assigned in `MainView`'s constructor body,
which runs after the field initialisers.

The failure would be thrown from the constructor, so `App.start()` would fail and the window
would never appear. With the `setValue` placed after the listeners, this crashes on every
start.

### How this code avoids it

Two decisions, in two classes:

1. **`FilterPanel` sets its initial values before it adds its listeners.** Constructing it
   never calls `onChange`, which `FilterPanelTest.constructionDoesNotFireOnChange` pins
   down.
2. **`MainView` calls `reload()` explicitly**, as the last line of its constructor, when
   every field is assigned.

The README calls the firing-during-construction behaviour convenient. It is, but it makes
correctness depend on the order of field initialisers, in another class, that nobody would
think to check. An explicit first `reload()` is one more line, and it can be read in place.

## 2. `getStyleClass().add(...)` instead of `setAll(...)` — describe what the user sees on the fourth refresh.

### In this code: nothing, because the bars are rebuilt

`SummaryPane.update` creates new `ProgressBar`s on every refresh. A new bar starts with a
fresh style class list, so `add` and `setAll` behave the same here. The manual check in
`TESTS.md` (step 14) will **not** show the bug with this design. It only appears once a bar
is **reused** across refreshes, which is the "updating rows in place" case the README warns
about.

### With reused bars: the colour sticks to the "worst" status it has ever had

Take one category's bar across four refreshes, while the user switches months:

| Refresh | Status | Style classes with `add` |
|---|---|---|
| 1 | OK | `progress-bar status-ok` |
| 2 | EXCEEDED | `progress-bar status-ok status-exceeded` |
| 3 | WARNING | `progress-bar status-ok status-exceeded status-warning` |
| 4 | OK | `progress-bar status-ok status-exceeded status-warning status-ok` |

On the fourth refresh the category is back on track, and the bar is **red**.

The reason is how CSS resolves the conflict. All four status selectors have the same
specificity, so the winner is the rule that comes **last in `app.css`**, not the class
added most recently. The order in the file is ok, warning, exceeded, no-budget. Once a bar
has collected `status-exceeded`, it is red for as long as it lives, whatever its status
says. Once it has collected `status-no-budget`, it is grey forever.

So what the user sees is a bar that turns amber or red and never goes back to green. The
percentage label underneath still says `40.00%  on track`, because labels are not affected.
The colour and the text disagree, and nothing reports an error.

## 3. The table and summary are two separate tasks. Construct the exact sequence of user actions that makes them briefly disagree.

### One action is enough

The two tasks share a **single** worker thread, and `reload()` submits the table first. So
after every change of filter, the table is updated before the summary query has even
started:

1. The window shows October 2026, in both the table and the panel.
2. The user picks **September 2026**. `reload()` queues `find(September)`, then
   `summarise(September)`.
3. The worker runs `find(September)`. Its result lands on the FX thread, and the table shows
   September.
4. **Now they disagree.** The table shows September and the panel still says October 2026,
   until the worker finishes `summarise(September)`.
5. The summary lands, and both show September.

The README describes the disagreement as needing two quick changes. On a single-thread
executor it happens on every change, for as long as the summary query takes. That is a
few milliseconds on this data, and visible on 50,000 rows.

### Two quick actions make it worse

1. October is showing in both places.
2. The user picks **September**. `find(Sep)` runs, and the table shows September. Then
   `summarise(Sep)` starts.
3. While `summarise(Sep)` is running, the user picks **August**.
4. `summarise(Sep)` finishes, but it is now stale on the summary stream, so it is
   discarded. The panel **still shows October**.
5. `find(Aug)` runs, and the table shows August.
6. **Three months on screen**: the table shows August, the panel shows October, and the
   combo says August. September has been fetched twice and shown once.
7. `summarise(Aug)` lands, and everything agrees.

It is brief and it fixes itself, and it is the trade the README describes. One task
returning both results would close the gap. Note that `runLatest` as the spec wrote it,
with **one** shared counter, would have been far worse than a brief disagreement. The
summary request would have superseded the table's on every reload, and **the table would
never have loaded at all**. That is why `BackgroundRunner` has a separate `Latest` stream
for each, and why `separateStreamsDoNotSupersedeEachOther` exists.

## 4. `SummaryPane.update` takes a `MonthSummary`. Name three things it would have to compute itself if it took `List<Expense>` instead — and which rule that breaks.

### What it would have to compute

1. **The totals.** `totalSpent` is a `BigDecimal` sum over the list. `entryCount` and
   `average` come from it too, and `average` needs a division with a chosen scale and
   rounding mode.
2. **The per-category breakdown.** Group by category, sum each group, and sort by `spent`
   descending, then by name, so that ties come out the same every time.
3. **Budget status and progress.** For each category it would need `percentUsed`, the
   `OK`/`WARNING`/`EXCEEDED`/`NO_BUDGET` decision, and the clamped bar fill. Each of those
   needs the **budgets**, which are not in a list of expenses. So the pane would also need
   `BudgetService`, and it would have to include budgeted categories with no spending,
   because `totalBudgeted` counts every budget.

`largest` could be added as a fourth: a max-by-amount, with a decision about what to do on
ties.

### The rule

**U-6: the UI holds no business logic.** Every item above is business logic, and every one
already exists in a tested form. `SummaryService` computes it twice, in SQL and with
streams, and sprint 11 asserts that the two agree. A third version in the view would be
untested, and it would round in its own way. The `BudgetStatus` thresholds are deliberately
decided on exact cents, by cross-multiplying, and a view that compared a rounded percentage
against 80 would put some values on the wrong side of the line.

### There is also a correctness bug

The obvious `List<Expense>` to pass is the one the table already has, and that list is
**filtered by category**. With `Groceries` selected, the summary would claim the month's
total is the groceries total, and every other category would disappear from the panel. A
`MonthSummary` is always for the whole month. `reload()` passes only `filter.month()` to
`summarise`, so the category filter changes the table and not the summary, which is what the
user expects.
