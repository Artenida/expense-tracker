# Sprint 18 · Build specification

---

## `ui/ErrorDialogs.java`

```java
public final class ErrorDialogs {

    private static final Logger LOG = Logger.getLogger(ErrorDialogs.class.getName());

    private ErrorDialogs() {}

    public static void show(Throwable error) {
        LOG.log(Level.SEVERE, "error surfaced to the user", error);

        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Expense Tracker");
        alert.setHeaderText(headerFor(error));
        alert.setContentText(messageFor(error));
        alert.showAndWait();
    }

    private static String headerFor(Throwable error) {
        return switch (error) {
            case ValidationException ignored      -> "That does not look right";
            case ExpenseNotFoundException ignored -> "Already gone";
            case CsvFormatException ignored       -> "The file could not be read";
            case StoreException ignored           -> "Could not reach the database";
            default                               -> "Something went wrong";
        };
    }

    private static String messageFor(Throwable error) {
        return switch (error) {
            case ValidationException e      -> String.join("\n", e.errors());
            case ExpenseNotFoundException e -> "That expense no longer exists. It may have "
                                             + "been deleted in another window. The list has "
                                             + "been refreshed.";
            case CsvFormatException e       -> e.getMessage()
                                             + "\n\nNothing was imported.";
            case StoreException e           -> databaseHint(e);
            default                         -> "See the log for details.";
        };
    }
}
```

### `databaseHint`

```java
private static String databaseHint(StoreException error) {
    String cause = error.getCause() == null ? "" : String.valueOf(error.getCause().getMessage());

    if (cause.contains("read-only") || cause.contains("READONLY")) {
        return "The database file is read-only. Check the file permissions.";
    }
    if (cause.contains("locked") || cause.contains("BUSY")) {
        return "The database is in use by another program. Close it and try again.";
    }
    return "The database could not be opened or written to. See the log for details.";
}
```

String matching on a driver message is fragile, and it is worth knowing that. The robust
version reads `SQLiteException.getResultCode()`, which would mean importing
`java.sql`-adjacent types into `ui` — forbidden by the guard test.

The right fix is for `StoreException` to carry a small enum of its own
(`READ_ONLY`, `LOCKED`, `OTHER`), set in `store` where the driver types are legal.
**That is the better design**; do it if you have the appetite, and note the trade-off if
you do not. The point of writing it down is that the next reader knows this was a
decision rather than an oversight.

`showAndWait()` from a background thread would throw. `ErrorDialogs.show` is only ever
called from `setOnFailed`, which runs on the FX thread — but adding a guard makes the
constraint explicit:

```java
if (!Platform.isFxApplicationThread()) {
    Platform.runLater(() -> show(error));
    return;
}
```

---

## Delete

### In `ExpenseTableView`

```java
public void setOnDelete(Consumer<ExpenseRow> handler) { this.onDelete = handler; }

private void buildRowFactory() {
    table.setRowFactory(view -> {
        TableRow<ExpenseRow> row = new TableRow<>();

        MenuItem edit   = new MenuItem("Edit…");
        MenuItem delete = new MenuItem("Delete");
        edit.setOnAction(e -> onEdit.accept(row.getItem()));
        delete.setOnAction(e -> onDelete.accept(row.getItem()));

        ContextMenu menu = new ContextMenu(edit, delete);
        row.contextMenuProperty().bind(
            Bindings.when(row.emptyProperty()).then((ContextMenu) null).otherwise(menu));

        row.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && !row.isEmpty()) onEdit.accept(row.getItem());
        });
        return row;
    });

    table.setOnKeyPressed(event -> {
        if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
            ExpenseRow selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) onDelete.accept(selected);
        }
    });
}
```

The cast `(ContextMenu) null` is needed because `then` is overloaded and the compiler
cannot infer which one from a bare `null`.

### In `MainView`

```java
private void onDelete(ExpenseRow row) {
    Expense expense = row.source();

    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
    confirm.initOwner(window());
    confirm.setTitle("Delete expense");
    confirm.setHeaderText("Delete this expense?");
    confirm.setContentText(Money.format(expense.amount()) + " — " + expense.description()
                         + "\non " + expense.date());

    if (confirm.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;

    runner.run(() -> { expenseService.delete(expense.id()); return null; },
               ignored -> reload(),
               error   -> { reload(); ErrorDialogs.show(error); });
}
```

Two details:

- `showAndWait().filter(ButtonType.OK::equals).isEmpty()` — `showAndWait` returns
  `Optional<ButtonType>`, empty if the dialog was dismissed. Filtering for OK handles
  both Cancel and dismissal in one expression.
- **`reload()` in the error handler too.** An `ExpenseNotFoundException` means the table
  is showing a row that is not in the database. Refreshing before showing the message is
  what makes the message's "the list has been refreshed" true.

---

## `ui/BudgetPane.java`

```java
public final class BudgetPane {

    private final TableView<BudgetRow> table = new TableView<>();
    private final ObservableList<BudgetRow> rows = FXCollections.observableArrayList();

    public BudgetPane(BudgetService budgets, BackgroundRunner runner, Runnable onChange) { ... }

    public void reload() { ... }
}
```

### `ui/model/BudgetRow.java`

```java
public final class BudgetRow {
    private final Category category;
    private final StringProperty limit;      // editable, so not read-only

    public BudgetRow(Category category, Optional<Budget> budget) {
        this.category = category;
        this.limit = new SimpleStringProperty(
            budget.map(b -> Money.format(b.monthlyLimit())).orElse(""));
    }
}
```

**One row per `Category`**, whether or not a budget exists — the specification's *"one
editable row per `Category`, showing the current limit"*. An empty string means unset.

`SimpleStringProperty`, not `ReadOnlyStringWrapper`, because `TextFieldTableCell` writes
back to it.

### Loading

```java
public void reload() {
    runner.run(budgetService::findAll, all -> {
        Map<Category, Budget> byCategory = all.stream()
            .collect(Collectors.toMap(Budget::category, b -> b));

        rows.setAll(Arrays.stream(Category.values())
            .map(c -> new BudgetRow(c, Optional.ofNullable(byCategory.get(c))))
            .toList());
    }, ErrorDialogs::show);
}
```

One query for all budgets, then the seven rows built from it. Not seven queries.

### Committing an edit

```java
limitColumn.setOnEditCommit(event -> {
    BudgetRow row = event.getRowValue();
    String raw = event.getNewValue().trim();

    if (raw.isEmpty()) { reload(); return; }        // clearing is not deleting — reload and move on

    List<String> errors = Validation.amount(raw);
    if (!errors.isEmpty()) {
        ErrorDialogs.show(new ValidationException(errors));
        reload();                                    // put the old value back
        return;
    }

    runner.run(() -> budgetService.setLimit(row.category(), Money.parse(raw)),
               saved   -> { reload(); onChange.run(); },
               error   -> { reload(); ErrorDialogs.show(error); });
});
```

`Validation.amount` again — the same rule as the expense dialog and the CSV importer.
Four call sites, one implementation.

`reload()` after a failure restores the displayed value from the database, so the cell
never shows something that was not saved.

`onChange.run()` is `MainView::reload` — changing a budget changes the summary, so the
right-hand panel must refresh. This is U-5 reaching across two panes.

---

## `ui/TopExpensesView.java`

A `TableView<ExpenseRow>` with the same columns and a spinner for N:

```java
Spinner<Integer> count = new Spinner<>(1, 50, 5);

public void reload(YearMonth month) {
    runner.runLatest(() -> expenseService.topExpenses(month, count.getValue()),
                     rows -> table.getItems().setAll(ExpenseRow.wrap(rows)),
                     ErrorDialogs::show);
}
```

The ordering and the `LIMIT` are sprint 09's SQL — the specification's *"done when:
ordering and `LIMIT` are done by the database, not by sorting an in-memory list."*

Resist the shortcut of reusing the already-loaded month and sorting it in Java. It would
work, it would be faster on a small month, and it would miss the point of the exercise.

---

## The typed amount column

Add to `ExpenseRow`:

```java
private final ReadOnlyObjectWrapper<BigDecimal> amountValue;
// ...
this.amountValue = new ReadOnlyObjectWrapper<>(source.amount());
public ReadOnlyObjectProperty<BigDecimal> amountValueProperty() { ... }
```

And in `ExpenseTableView`, replace the `String` amount column with the typed one from
README. Keep `amountProperty()` — the CSV export and any plain-text use still want the
formatted string.

---

## `MainView` — the `TabPane`

```java
TabPane tabs = new TabPane();
tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
tabs.getTabs().addAll(
    new Tab("Expenses",     tableView.getRoot()),
    new Tab("Top expenses", topExpensesView.getRoot()),
    new Tab("Budgets",      budgetPane.getRoot()));
root.setCenter(tabs);
```

`reload()` now refreshes all three plus the summary. Four `runLatest` calls on one
worker thread — measure it before optimising; on a local SQLite file it is imperceptible.
