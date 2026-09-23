# Sprint 18 · Delete, budgets and top expenses

**Time:** ~2.5 hours · **Prerequisites:** 17 · **Produces:** delete, `ErrorDialogs`, `BudgetPane`, the top-N view

---

## Purpose

Three remaining capabilities, plus the class that makes the whole application safe to
put in front of a person: `ErrorDialogs`.

Everything up to now has printed stack traces to the console when something went wrong.
That was an honest placeholder. This sprint replaces it with the specification's U-3:
*"Could not save the expense — the database file is read-only" is useful; a wall of
`at com.expensetracker...` is not.*

## New concepts

### 1. Translating exceptions into sentences

```java
public static void show(Throwable error) {
    String message = switch (error) {
        case ValidationException e      -> String.join("\n", e.errors());
        case ExpenseNotFoundException e -> "That expense no longer exists. "
                                         + "It may have been deleted in another window.";
        case StoreException e           -> "Could not reach the database. " + hint(e);
        default                         -> "Something went wrong. See the log for details.";
    };
    LOG.log(Level.SEVERE, "UI error", error);     // the full trace goes here
    new Alert(Alert.AlertType.ERROR, message).showAndWait();
}
```

This is a **switch on patterns** (Java 21). Each `case` matches a type and binds the
variable, and the arrow form means no `break` and no fall-through.

The `default` case is required: the compiler cannot prove you have covered every possible
`Throwable`, and an uncovered one at runtime would throw `MatchException` — turning an
error report into a second error.

Two audiences, two outputs, in the same method: the **user** gets a sentence they can act
on, the **log** gets the full trace. Doing only one of those is the common mistake in
both directions — a dialog full of stack trace, or a friendly message with the cause
thrown away.

### 2. `ContextMenu` and key handling

```java
ContextMenu menu = new ContextMenu();
MenuItem delete = new MenuItem("Delete");
delete.setOnAction(e -> onDelete.accept(row.getItem()));
menu.getItems().add(delete);

row.contextMenuProperty().bind(
    Bindings.when(row.emptyProperty())
            .then((ContextMenu) null)
            .otherwise(menu));
```

`Bindings.when(...).then(...).otherwise(...)` is JavaFX's conditional binding — a
ternary that re-evaluates when its condition changes. Here it means "no context menu on
empty rows", which stops the user right-clicking blank space and getting a Delete option
for nothing.

The `Delete` key is separate, on the table:

```java
table.setOnKeyPressed(event -> {
    if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
        ExpenseRow selected = table.getSelectionModel().getSelectedItem();
        if (selected != null) onDelete.accept(selected);
    }
});
```

`BACK_SPACE` as well as `DELETE`, because on a Mac laptop keyboard the key labelled
"delete" sends `BACK_SPACE`. Handling only `DELETE` makes the feature invisible to most
Mac users.

### 3. A confirmation that names what it will destroy

```java
Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
confirm.setHeaderText("Delete this expense?");
confirm.setContentText(Money.format(expense.amount()) + " — " + expense.description()
                     + " on " + expense.date());
```

"Are you sure?" is not a confirmation, it is a speed bump — people click through it
without reading. Naming the amount, the description and the date lets the user notice
they selected the wrong row, which is the entire point of asking.

### 4. Editable table cells

```java
TableColumn<BudgetRow, String> limit = new TableColumn<>("Monthly limit");
limit.setCellFactory(TextFieldTableCell.forTableColumn());
limit.setOnEditCommit(event -> onLimitChanged(event.getRowValue(), event.getNewValue()));
table.setEditable(true);
```

Three things must all be true for editing to work, and missing any one of them fails
silently:

- `table.setEditable(true)`
- the **column** has a cell factory that can edit
- the column's value must be settable — a read-only property will not commit

`setOnEditCommit` fires when the user presses Enter. Pressing Escape or clicking away
cancels, and no event fires. That is the behaviour you want, and it means an edit only
reaches the database on a deliberate commit.

### 5. `TabPane`

```java
TabPane tabs = new TabPane();
tabs.getTabs().addAll(new Tab("Expenses", expenseTable),
                      new Tab("Top expenses", topTable),
                      new Tab("Budgets", budgetPane));
tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
```

`UNAVAILABLE` removes the little × from each tab. The default lets a user close a tab
with no way to bring it back, which is almost never what an application wants.

## The amount column sorting bug

Sprint 14 left this open and it is time to fix it. The amount column holds `String`s, so
sorting is alphabetical: `100.00` sorts before `24.90`, because `'1' < '2'`.

The fix is a typed column:

```java
TableColumn<ExpenseRow, BigDecimal> amount = new TableColumn<>("Amount");
amount.setCellValueFactory(c -> c.getValue().amountValueProperty());   // ObjectProperty<BigDecimal>
amount.setCellFactory(column -> new TableCell<>() {
    @Override protected void updateItem(BigDecimal value, boolean empty) {
        super.updateItem(value, empty);
        setText(empty || value == null ? null : Money.format(value));
    }
});
```

The column now **holds** a `BigDecimal` and **displays** a formatted string. Sorting uses
the real value, because `BigDecimal` is `Comparable` and compares numerically.

This is the general lesson: **store the value, format in the cell.** A column whose type
is `String` can only ever sort as text, and the same problem would hit a date column
stored as a string — ISO dates happen to sort correctly as text, which is luck, not
design.

`ExpenseRow` needs one more property for this, and keeps the formatted one for anything
that wants a plain string.

## What you build

- `ui/ErrorDialogs`
- Delete: context menu, `Delete`/`Backspace`, confirmation
- `ui/BudgetPane` — one editable row per category
- `ui/TopExpensesView` — the N largest
- `TabPane` in the centre, and the typed amount column

## Definition of done

- [ ] Deleting asks first, and names the expense
- [ ] Deleting a row another process removed shows a readable sentence, not a trace
- [ ] `Delete` **and** `Backspace` both work
- [ ] Every category has a budget row; editing one upserts
- [ ] Setting a budget twice leaves one row with the newer value
- [ ] Top expenses come back ordered and limited **by the database**
- [ ] Sorting by Amount is numeric: `100.00` after `24.90` descending
- [ ] No stack trace is ever visible to the user

---

## Reading check

1. The `switch` has a `default` even though every exception in the project is covered.
   Why does the compiler insist, and what happens at runtime without it?
2. `setOnEditCommit` does not fire when the user presses Escape. Why is that the right
   behaviour for a cell that writes to a database?
3. Sorting by a `String` amount column puts `100.00` before `24.90`. Why do ISO date
   strings not have the same problem?
4. `ErrorDialogs` logs the trace and shows a sentence. What is lost if you show the
   trace, and what is lost if you do not log it?
