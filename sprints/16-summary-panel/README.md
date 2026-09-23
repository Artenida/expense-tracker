# Sprint 16 · Filters and the summary panel

**Time:** ~3 hours · **Prerequisites:** 15 · **Produces:** the filter panel, a real `reload()`, `SummaryPane`, the status CSS

---

## Purpose

The window becomes usable. Two combo boxes on the left drive one query; the panel on the
right shows where the money went. Both go through `BackgroundRunner`, and both are
refreshed by a single `reload()` method that every later mutation will call.

The specification has two rules that this sprint is the first real test of, and both are
easy to break in ways that look fine:

- **U-5, refresh is explicit and centralised.** One `reload()`, called by everything.
- **U-6, the UI holds no business logic.** The panel formats a `MonthSummary` and does
  nothing else. No `spent / limit` anywhere in a view class.

## New concepts

### 1. `ComboBox` and `StringConverter`

A `ComboBox<T>` holds objects and needs to know how to display them:

```java
ComboBox<YearMonth> month = new ComboBox<>();
month.setConverter(new StringConverter<>() {
    @Override public String toString(YearMonth ym) {
        return ym == null ? "" : ym.format(DateTimeFormatter.ofPattern("MMMM yyyy"));
    }
    @Override public YearMonth fromString(String s) { return null; }   // not editable
});
```

`fromString` returning `null` is normal for a non-editable combo — it is only called when
the user can type. Leaving it unimplemented rather than pretending to parse is the honest
version.

The `toString` must handle `null`, because the combo asks about its (initially empty)
selection.

### 2. The "All" entry

The category filter needs an entry meaning "no category filter". Three ways, and the
choice is worth making deliberately:

| Approach | Verdict |
|---|---|
| Add an `ALL` constant to the `Category` enum | **No.** It would appear in the dialog, in the summary, in the CSV — a UI concern polluting the domain. |
| A wrapper type in `ui/model` | Correct, and heavier than this needs |
| `null` as the first item, rendered by the converter | What we do. Contained entirely in one converter. |

```java
category.getItems().add(null);                       // "All"
category.getItems().addAll(Category.values());
```

This is the one place in the project where `null` is used deliberately. It is legal —
the rule is that no *public method returns* `null` — and it is confined to one control
and one converter. Write a comment saying so, because it will otherwise look like an
oversight.

`currentFilter()` turns it back into the `Optional<Category>` sprint 05 defined, and from
there nothing else in the application ever sees the `null`.

### 3. Listeners

```java
month.valueProperty().addListener((observable, oldValue, newValue) -> reload());
```

Three parameters: the property that changed, the previous value, the new one. Most
handlers ignore the first two — `(obs, old, now) -> reload()` is the common shape.

`valueProperty()` fires when the selection changes, whether by the user or by code. That
matters: setting the initial month in the constructor fires the listener and triggers the
first load. Convenient, and worth knowing rather than discovering, because it means
`reload()` must work before the constructor has finished.

### 4. Two queries per reload

```java
private void reload() {
    ExpenseFilter filter = currentFilter();
    YearMonth month = filter.month();

    runner.runLatest(() -> expenseService.find(filter), tableView::setRows, this::showError);
    runner.runLatest(() -> summaryService.summarise(month), summaryPane::update, this::showError);
}
```

Two independent calls, each with its own stamp, each landing on the FX thread when ready.

Note both go through `runLatest`. Change the month three times quickly and only the last
pair is displayed — the table and the panel stay consistent with each other because both
are stamped by the same rapid sequence of user actions.

> They could be one `Task` returning a pair, which would guarantee the table and panel
> always show the same month. Two calls can in principle interleave so that the table
> shows September and the panel August, for the moment before the second lands. On a
> local database that window is a few milliseconds. Worth knowing the trade — and worth
> not over-engineering it.

### 5. Style classes from the enum, not `if`

The specification is specific: *"The colour is a CSS class driven by the enum, not an
`if` chain in the view."*

```java
bar.getStyleClass().setAll("progress-bar", status.cssClass());
```

Sprint 02 put `cssClass()` on `BudgetStatus`. Add a fifth status and the view needs no
change at all.

**`setAll`, not `add`.** `getStyleClass()` is a list, and `add` appends. A panel
refreshed five times accumulates five status classes, the last one does not necessarily
win, and the bar ends up an unpredictable colour. This is one of the most common JavaFX
bugs and it only appears after a few refreshes — which is exactly when you have stopped
looking.

Because the panel is rebuilt from scratch each update, the point is partly moot here.
Know it anyway: the day you switch to updating rows in place, it bites.

### 6. `ProgressBar`

```java
ProgressBar bar = new ProgressBar(categoryTotal.progress());
```

Takes a `double` from 0 to 1. Sprint 05's `CategoryTotal.progress()` already clamps at
1.0, so an exceeded budget is a full bar and never an over-full one — the specification's
rule, satisfied by the domain rather than by the view.

A value of `-1` means indeterminate (the animated barber-pole). Sprint 19 uses that.

## What you build

- `ui/FilterPanel` — two combo boxes
- `ui/SummaryPane` — header, category rows, footer stats
- `MainView.reload()` — the one refresh method
- `app.css` — the four status colours

## Definition of done

- [ ] Changing either combo re-runs **one** filtered query — nothing loads everything
      and filters in Java
- [ ] The summary refreshes with the table
- [ ] Bars are green / amber / red / grey by status, from CSS
- [ ] An exceeded bar is full, not over-full
- [ ] A month with no expenses shows zeroes, not a blank panel and not an error
- [ ] The table's sort order survives a reload (the sprint 14 `SortedList` check)
- [ ] No `spent / limit` arithmetic anywhere in `ui`

---

## Reading check

1. Setting the initial month in the constructor triggers `reload()` through the listener.
   What must already be constructed for that to work, and what happens if it is not?
2. `getStyleClass().add(...)` instead of `setAll(...)` — describe what the user sees on
   the fourth refresh.
3. The table and summary are two separate tasks. Construct the exact sequence of user
   actions that makes them briefly disagree.
4. `SummaryPane.update` takes a `MonthSummary`. Name three things it would have to
   compute itself if it took `List<Expense>` instead — and which rule that breaks.
