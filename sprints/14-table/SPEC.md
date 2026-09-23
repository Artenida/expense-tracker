# Sprint 14 · Build specification

---

## `ui/model/ExpenseRow.java`

```java
package com.expensetracker.ui.model;

public final class ExpenseRow {

    private final Expense source;

    private final ReadOnlyStringWrapper date;
    private final ReadOnlyStringWrapper category;
    private final ReadOnlyStringWrapper description;
    private final ReadOnlyStringWrapper amount;

    public ExpenseRow(Expense source) {
        this.source      = Objects.requireNonNull(source);
        this.date        = new ReadOnlyStringWrapper(source.date().toString());
        this.category    = new ReadOnlyStringWrapper(source.category().displayName());
        this.description = new ReadOnlyStringWrapper(source.description());
        this.amount      = new ReadOnlyStringWrapper(Money.format(source.amount()));
    }

    public Expense source() { return source; }

    public ReadOnlyStringProperty dateProperty()        { return date.getReadOnlyProperty(); }
    public ReadOnlyStringProperty categoryProperty()    { return category.getReadOnlyProperty(); }
    public ReadOnlyStringProperty descriptionProperty() { return description.getReadOnlyProperty(); }
    public ReadOnlyStringProperty amountProperty()      { return amount.getReadOnlyProperty(); }

    public static List<ExpenseRow> wrap(List<Expense> expenses) {
        return expenses.stream().map(ExpenseRow::new).toList();
    }
}
```

### The three conversions, and where they come from

| Column | Value | Source |
|---|---|---|
| date | `2026-09-15` | `LocalDate.toString()` — ISO by definition |
| category | `Groceries` | `displayName()`, sprint 02 — **not** `name()` |
| amount | `24.90` | `Money.format`, sprint 03 — the single rounding point |

Display name for the category, enum name for storage and CSV. Two different audiences,
two different strings, and sprint 02 put both on the enum so neither is computed here.

### Why `ReadOnlyStringWrapper`

`ReadOnlyStringWrapper` holds the value and hands out a `ReadOnlyStringProperty` through
`getReadOnlyProperty()`. The table can observe it; nothing can set it.

That matches the object: an `ExpenseRow` describes an immutable `Expense` at a moment in
time. Editing produces a new `Expense`, a reload produces a new `ExpenseRow`. Nothing is
ever updated in place — which is sprint 10's "the services are stateless" rule reaching
the UI.

`wrap` is a static helper so `MainView` reads as `rows.setAll(ExpenseRow.wrap(found))`.

---

## `ui/ExpenseTableView.java`

```java
public final class ExpenseTableView {

    private final TableView<ExpenseRow>       table = new TableView<>();
    private final ObservableList<ExpenseRow>  rows  = FXCollections.observableArrayList();
    private final BorderPane                  root  = new BorderPane();

    public ExpenseTableView() {
        buildColumns();
        buildSorting();
        root.setTop(buildToolbar());
        root.setCenter(table);
    }

    public void setRows(List<Expense> expenses) { rows.setAll(ExpenseRow.wrap(expenses)); }
    public Node getRoot()                       { return root; }
}
```

### Columns

```java
private void buildColumns() {
    TableColumn<ExpenseRow, String> date = new TableColumn<>("Date");
    date.setCellValueFactory(c -> c.getValue().dateProperty());
    date.setPrefWidth(110);

    TableColumn<ExpenseRow, String> category = new TableColumn<>("Category");
    category.setCellValueFactory(c -> c.getValue().categoryProperty());
    category.setPrefWidth(120);

    TableColumn<ExpenseRow, String> description = new TableColumn<>("Description");
    description.setCellValueFactory(c -> c.getValue().descriptionProperty());
    description.setPrefWidth(320);

    TableColumn<ExpenseRow, String> amount = new TableColumn<>("Amount");
    amount.setCellValueFactory(c -> c.getValue().amountProperty());
    amount.setPrefWidth(100);
    amount.getStyleClass().add("amount-column");

    table.getColumns().setAll(List.of(date, category, description, amount));
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    table.setPlaceholder(new Label("No expenses for this selection"));
}
```

`setPlaceholder` replaces the default "No content in table", which is what the user sees
before adding anything and after filtering to an empty month. Worth setting to something
that reads like the application rather than like a toolkit.

The resize policy makes the columns fill the available width instead of leaving a grey
gap on the right.

### Sorting

```java
private void buildSorting() {
    SortedList<ExpenseRow> sorted = new SortedList<>(rows);
    sorted.comparatorProperty().bind(table.comparatorProperty());
    table.setItems(sorted);
}
```

Three lines, in this order. The binding direction is table → list; README §5 explains
what the other direction does.

`setRows` still writes to `rows`, the underlying list. `SortedList` is a view; you never
write to it.

### The toolbar

```java
private Node buildToolbar() {
    Button add    = new Button("Add expense");
    Button impor  = new Button("Import CSV");
    Button export = new Button("Export CSV");

    add.setDisable(true);         // sprint 17
    impor.setDisable(true);       // sprint 19
    export.setDisable(true);      // sprint 19

    ToolBar bar = new ToolBar(add, impor, export);
    bar.getStyleClass().add("expense-toolbar");
    return bar;
}
```

Disabled buttons with a comment naming the sprint that enables them. A visible, honest
placeholder beats a button that silently does nothing.

---

## `MainView` — the temporary load

```java
private final ExpenseTableView tableView = new ExpenseTableView();

// TODO(sprint-15): this blocks the FX Application Thread. Move to BackgroundRunner.
private void reloadTemporarily() {
    ExpenseFilter filter = ExpenseFilter.of(YearMonth.now().minusMonths(1));
    tableView.setRows(expenseService.find(filter));
}
```

`YearMonth.now().minusMonths(1)` — last month, so there is data to look at without
worrying about `Expense.create` rejecting future dates in the current one.

Call it from the constructor. Sprint 16 replaces it with a real `reload()` driven by the
filter controls.

Set `root.setCenter(tableView.getRoot())` in place of sprint 13's placeholder.

---

## `app.css` additions

```css
.amount-column { -fx-alignment: CENTER-RIGHT; }

.table-view .column-header { -fx-font-weight: bold; }

.table-row-cell:selected { -fx-background-color: #d6e4ff; }
```

`-fx-alignment: CENTER-RIGHT` on the column applies to every cell in it. The values are
JavaFX's `Pos` enum constants with hyphens — `CENTER-RIGHT`, not `right`. Another place
where the resemblance to web CSS misleads.

---

## Seeding data to look at

There is no Add dialog until sprint 17. Put a few rows in with the SQLite CLI:

```sh
sqlite3 data/expenses.db "INSERT INTO expenses VALUES
  ('a','2490','GROCERIES','Weekly shop','2026-08-15','2026-08-15T10:00:00Z',NULL),
  ('b','1200','TRANSPORT','Bus fare','2026-08-15','2026-08-15T11:00:00Z',NULL),
  ('c','9950','HOUSING','Electricity','2026-08-03','2026-08-03T09:00:00Z',NULL);"
```

Note the trailing `NULL` for the `note` column sprint 06's V2 added — a small reminder
that the table has a column the application never mentions.
