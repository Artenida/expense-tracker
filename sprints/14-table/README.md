# Sprint 14 · The table

**Time:** ~2.5 hours · **Prerequisites:** 13 · **Produces:** `ExpenseRow`, `ExpenseTableView`

---

## Purpose

Get expenses on the screen. Four columns, sortable, formatted properly — and, more
importantly, the adapter that lets a JavaFX `TableView` display a domain object that
knows nothing about JavaFX.

This sprint **deliberately breaks one rule**, and sprint 15 fixes it. The data is loaded
on the FX Application Thread, which the specification forbids. Doing it wrong once, on
40 rows where it works perfectly, and then feeling it break on 50 000, teaches the rule
better than obeying it from the start. The temporary call is marked so it cannot be
forgotten.

## New concepts

### 1. `ObservableList` — a list that announces changes

```java
ObservableList<ExpenseRow> rows = FXCollections.observableArrayList();
table.setItems(rows);

rows.setAll(newRows);      // the table redraws itself
```

An ordinary `List` with a listener mechanism attached. `TableView` subscribes when you
call `setItems`, so adding, removing or replacing elements updates the display with no
further code.

This is the JavaFX model in miniature: you change **data**, and the view reacts. You
never tell the table to repaint.

`setAll(...)` replaces the entire contents in one notification. `clear()` followed by
`addAll(...)` produces two, and the table will flicker.

### 2. Properties — a value that can be observed

```java
private final StringProperty description = new SimpleStringProperty();
```

A `Property` wraps a value and lets others watch it. `TableView` uses them to know when
a cell's content changes without polling every row.

The family is regular: `StringProperty`, `IntegerProperty`, `ObjectProperty<T>`, each
with a `Simple...` implementation and a `ReadOnly...Wrapper` for values nothing outside
the class should set. `ExpenseRow` is immutable, so its properties are read-only
wrappers.

### 3. The adapter — the rule this sprint exists to protect

The tempting move is to put the properties on `Expense`:

```java
public class Expense {
    private final StringProperty description;   // NO
}
```

It would work, and it would put `javafx.beans.property` in the domain — so `Expense`
could not be constructed in a test without a JavaFX toolkit, could not be reused by a
web version, and would drag a UI framework through every layer. Sprint 04's guard test
would fail, which is exactly what that test is for.

Instead:

```java
public final class ExpenseRow {
    private final Expense source;                       // the real thing
    private final ReadOnlyStringWrapper description;    // the view's copy
}
```

One small class in `ui/model`, and the domain stays pure. `source()` gives the dialog in
sprint 17 the actual `Expense` back when it needs to edit it.

The pattern has a name — **adapter** — and this is a textbook instance: two interfaces
that should not know about each other, joined by a class that knows both.

### 4. `TableColumn` and cell value factories

```java
TableColumn<ExpenseRow, String> description = new TableColumn<>("Description");
description.setCellValueFactory(cell -> cell.getValue().descriptionProperty());
```

Two type parameters: the **row** type and the **cell** type. The cell value factory is
asked, per row, "which observable holds this column's value for this row?" — and returns
the property, not the string. The table then watches that property.

`cell.getValue()` returns the `ExpenseRow`, which reads oddly the first few times.
`CellDataFeatures` is the parameter type; `getValue()` is the row it describes.

### 5. `SortedList` — the gotcha that will bite you

The specification asks for a sortable table whose sort survives. The obvious code has a
bug:

```java
table.setItems(rows);           // plain ObservableList
rows.setAll(freshData);         // every reload silently drops the user's sort order
```

The user sorts by amount, changes month, and the table is back to date order with no
explanation. The fix:

```java
SortedList<ExpenseRow> sorted = new SortedList<>(rows);
sorted.comparatorProperty().bind(table.comparatorProperty());
table.setItems(sorted);
```

`SortedList` is a **view** over `rows` that keeps itself ordered. Binding its comparator
to the table's means clicking a header updates the comparator, which reorders the view,
which redraws the table. Changes still go into `rows`; `sorted` follows.

Getting the binding backwards — `table.comparatorProperty().bind(sorted.comparatorProperty())` —
compiles and produces a table that cannot be sorted at all. Read the direction: the
table is the **source** of the comparator, the list is the consumer.

### 6. Formatting belongs in the adapter

The amount column shows `24.90`, right-aligned. Both facts belong in `ExpenseRow` and
the column setup, not in `Expense`:

- `Money.format(expense.amount())` produces the string — sprint 03's one rounding point.
- Right alignment is a CSS class on the column.

Sorting then has a wrinkle worth noticing: a `String` column of amounts sorts
`"100.00"` before `"24.90"`, because that is alphabetical order. Sprint 18 fixes it
properly with a typed column; note it now and do not pretend the string version is
correct.

## The rule this sprint breaks

`MainView` calls `expenseService.find(filter)` directly, on the FX thread. Mark it:

```java
// TODO(sprint-15): this blocks the FX Application Thread. Move to BackgroundRunner.
List<Expense> found = expenseService.find(filter);
```

Before you delete that comment in sprint 15, seed 50 000 rows and watch the window
freeze. That experience is the sprint's real deliverable.

## What you build

- `ui/model/ExpenseRow` — the adapter
- `ui/ExpenseTableView` — four columns, `SortedList`, a toolbar with disabled buttons
- `MainView` wiring, with the temporary synchronous load

## Definition of done

- [ ] Expenses added through a test appear in the window
- [ ] Amounts show two decimals, right-aligned
- [ ] Dates show as ISO, categories by **display name**
- [ ] Clicking a header sorts; the sort survives a `setAll`
- [ ] Spec test 12 passes
- [ ] The guard tests still pass — `ExpenseRow` is in `ui/model`, `Expense` is untouched

---

## Reading check

1. `setCellValueFactory` returns a property rather than a `String`. What does the table
   do with the property that it could not do with a value?
2. You bind the comparator the wrong way round. It compiles. What is the symptom?
3. `ExpenseRow` wraps an `Expense` and also keeps `source()`. Why not rebuild the
   `Expense` from the four displayed strings when the dialog needs it?
4. `rows.setAll(list)` versus `rows.clear(); rows.addAll(list);` — what does the user
   see differently?
