# Sprint 16 · Build specification

---

## `ui/FilterPanel.java`

```java
public final class FilterPanel {

    private final ComboBox<YearMonth> month    = new ComboBox<>();
    private final ComboBox<Category>  category = new ComboBox<>();
    private final VBox root = new VBox(12);

    public FilterPanel(Runnable onChange) { ... }

    public ExpenseFilter currentFilter() {
        YearMonth selected = month.getValue();
        Category  chosen   = category.getValue();      // null means "All"
        return chosen == null ? ExpenseFilter.of(selected)
                              : ExpenseFilter.of(selected, chosen);
    }

    public Node getRoot() { return root; }
}
```

`onChange` is a `Runnable` — `MainView::reload`. The panel does not know what happens
when the filter changes, which keeps it from reaching into the table or the summary.

### The month combo

```java
YearMonth now = YearMonth.now();
for (int i = 0; i < 24; i++) month.getItems().add(now.minusMonths(i));
month.setValue(now);
```

Twenty-four months back, newest first. No future months: sprint 04 rejects future dates,
so they could only ever be empty.

```java
month.setConverter(new StringConverter<YearMonth>() {
    private final DateTimeFormatter format = DateTimeFormatter.ofPattern("MMMM yyyy");
    @Override public String toString(YearMonth value) {
        return value == null ? "" : value.format(format);
    }
    @Override public YearMonth fromString(String text) { return null; }
});
```

`"MMMM yyyy"` gives `September 2026`, matching the specification's summary header.

### The category combo

```java
// null is the "All" entry. This is the one deliberate null in the project:
// it lives in this control and this converter, and currentFilter() turns it
// back into Optional.empty() before anything else sees it.
category.getItems().add(null);
category.getItems().addAll(Category.values());
category.setValue(null);

category.setConverter(new StringConverter<Category>() {
    @Override public String toString(Category value) {
        return value == null ? "All categories" : value.displayName();
    }
    @Override public Category fromString(String text) { return null; }
});
```

`displayName()` again — the user never sees `GROCERIES`.

### Wiring

```java
month.valueProperty().addListener((obs, old, now) -> onChange.run());
category.valueProperty().addListener((obs, old, now) -> onChange.run());

root.setPadding(new Insets(16));
root.getChildren().addAll(new Label("Month"), month, new Label("Category"), category);
root.setPrefWidth(200);
month.setMaxWidth(Double.MAX_VALUE);
category.setMaxWidth(Double.MAX_VALUE);
```

`setMaxWidth(Double.MAX_VALUE)` makes the combos fill the panel width. Without it they
size to their content and the panel looks ragged — a `ComboBox` does not stretch by
default.

---

## `ui/SummaryPane.java`

```java
public final class SummaryPane {

    private final VBox root = new VBox(10);

    public SummaryPane() {
        root.setPadding(new Insets(16));
        root.setPrefWidth(300);
        root.getStyleClass().add("summary-pane");
    }

    public void update(MonthSummary summary) {
        root.getChildren().setAll(
            header(summary),
            new Separator(),
            categoryRows(summary),
            new Separator(),
            footer(summary));
    }

    public Node getRoot() { return root; }
}
```

`update` rebuilds the children from scratch. For a dozen rows that is simpler and less
bug-prone than diffing, and it makes the accumulating-style-class problem impossible.

### Header

```java
private Node header(MonthSummary s) {
    Label title = new Label(s.yearMonth().format(DateTimeFormatter.ofPattern("MMMM yyyy")));
    title.getStyleClass().add("summary-title");

    Label totals = new Label("spent " + Money.format(s.totalSpent())
                           + " of " + Money.format(s.totalBudgeted()) + " budgeted");
    totals.getStyleClass().add("summary-totals");

    return new VBox(4, title, totals);
}
```

The specification's `September 2026 — spent 236.40 of 950.00 budgeted`, on two lines so
it fits a 300 px panel.

`Money.format` for both — the single formatting point, reused rather than
`String.format("%.2f", ...)`, which would round independently and could disagree.

### Category rows

```java
private Node categoryRow(CategoryTotal total) {
    Label name = new Label(total.category().displayName());
    name.getStyleClass().add("category-name");

    Label amounts = new Label(total.limit().isPresent()
        ? Money.format(total.spent()) + " / " + Money.format(total.limit().get())
        : Money.format(total.spent()) + " / —");

    ProgressBar bar = new ProgressBar(total.progress());
    bar.setMaxWidth(Double.MAX_VALUE);
    bar.getStyleClass().setAll("progress-bar", total.status().cssClass());   // setAll!

    Label note = new Label(total.status() == BudgetStatus.NO_BUDGET
        ? total.status().label()
        : total.percentUsed().toPlainString() + "%  " + total.status().label());
    note.getStyleClass().add("category-note");

    HBox line = new HBox(8, name, new Spacer(), amounts);
    return new VBox(2, line, bar, note);
}
```

Everything displayed comes off the `CategoryTotal` — `progress()`, `percentUsed()`,
`status()`, `label()`. **No arithmetic in this method.** That is U-6, and the way to
check it is that the word `divide` and the character `/` (as an operator) do not appear.

`total.limit().get()` is safe only inside the `isPresent()` branch. `orElseThrow()` would
be more idiomatic; `map(...).orElse("—")` more idiomatic still.

### Footer

```java
private Node footer(MonthSummary s) {
    VBox box = new VBox(2);
    box.getChildren().add(new Label("Entries: " + s.entryCount()));
    box.getChildren().add(new Label("Average: " + Money.format(s.average())));

    s.largest().ifPresent(e -> box.getChildren().add(
        new Label("Largest: " + Money.format(e.amount()) + " — " + e.description())));

    box.getStyleClass().add("summary-footer");
    return box;
}
```

`ifPresent` is why sprint 05 made `largest` an `Optional`: a month with no expenses
simply has no "Largest" line, with no null check and no conditional.

---

## `MainView` — the real `reload()`

```java
private void reload() {
    ExpenseFilter filter = filterPanel.currentFilter();      // on the FX thread

    runner.runLatest(() -> expenseService.find(filter),
                     tableView::setRows,
                     this::showError);

    runner.runLatest(() -> summaryService.summarise(filter.month()),
                     summaryPane::update,
                     this::showError);
}
```

**Everything that changes data calls this.** Sprint 17's dialog, sprint 18's delete and
budget edits, sprint 19's import. Never patch the `ObservableList` by hand — the
specification's U-5, and its warning that drifting out of sync "costs an evening".

The construction order in `MainView` matters, because setting the initial combo value
fires the listener:

```java
this.tableView   = new ExpenseTableView();
this.summaryPane = new SummaryPane();
this.filterPanel = new FilterPanel(this::reload);   // may call reload() immediately
```

Build the things `reload()` touches **before** the thing that triggers it.

---

## `app.css` — the status colours

```css
.summary-pane  { -fx-background-color: #fafafc; }
.summary-title  { -fx-font-size: 16px; -fx-font-weight: bold; }
.summary-totals { -fx-text-fill: #555; }
.category-name  { -fx-font-weight: bold; }
.category-note  { -fx-font-size: 11px; -fx-text-fill: #777; }
.summary-footer { -fx-font-size: 12px; -fx-text-fill: #444; }

.progress-bar { -fx-pref-height: 10px; }

.status-ok        > .bar { -fx-background-color: #2e9e4f; }
.status-warning   > .bar { -fx-background-color: #e0a018; }
.status-exceeded  > .bar { -fx-background-color: #d03a30; }
.status-no-budget > .bar { -fx-background-color: #b8b8bd; }
```

`> .bar` is the substructure selector. A JavaFX `ProgressBar` is built from internal
nodes — `.track` and `.bar` — and colouring the `ProgressBar` itself would colour the
background. This is the part of JavaFX CSS with no web equivalent, and the only reliable
way to discover the names is the official *JavaFX CSS Reference Guide*.

The class names match `BudgetStatus.cssClass()` exactly. A typo here shows up as a
default-blue bar, not an error — JavaFX ignores selectors that match nothing.
