# Sprint 17 · Build specification

`src/main/java/com/expensetracker/ui/ExpenseDialog.java`

---

## The shape

```java
public final class ExpenseDialog {

    private final Dialog<Expense> dialog = new Dialog<>();

    private final TextField        amountField      = new TextField();
    private final ComboBox<Category> categoryBox    = new ComboBox<>();
    private final TextField        descriptionField = new TextField();
    private final DatePicker       datePicker       = new DatePicker();

    private final Label amountError      = errorLabel();
    private final Label descriptionError = errorLabel();
    private final Label dateError        = errorLabel();

    private final Expense existing;    // null when adding

    public static Optional<Expense> forNew(Window owner) {
        return new ExpenseDialog(owner, null).showAndWait();
    }

    public static Optional<Expense> forEditing(Window owner, Expense existing) {
        return new ExpenseDialog(owner, Objects.requireNonNull(existing)).showAndWait();
    }
}
```

Two static factories, a private constructor. Same reasoning as sprint 04's
`create`/`restore`: the two situations are different enough that naming them beats a
boolean parameter.

`Window owner` makes the dialog modal to the main window — it centres on it and blocks
interaction with it. Pass `mainView.getRoot().getScene().getWindow()`.

---

## Layout

A `GridPane`, two columns, with the error label under each field:

```java
GridPane grid = new GridPane();
grid.setHgap(10);
grid.setVgap(6);
grid.setPadding(new Insets(16));

grid.addRow(0, new Label("Amount"),      amountField);
grid.add(amountError, 1, 1);
grid.addRow(2, new Label("Category"),    categoryBox);
grid.addRow(3, new Label("Description"), descriptionField);
grid.add(descriptionError, 1, 4);
grid.addRow(5, new Label("Date"),        datePicker);
grid.add(dateError, 1, 6);

dialog.getDialogPane().setContent(grid);
dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
```

Error labels in column 1 (under the field, not under the label) so they line up with what
they are about. There is no error row for the category — a `ComboBox` cannot hold an
invalid value.

```java
private static Label errorLabel() {
    Label label = new Label();
    label.getStyleClass().add("field-error");
    label.setVisible(false);
    label.setManaged(false);
    return label;
}
```

## The controls

```java
categoryBox.getItems().setAll(Category.values());        // no null here — "All" is a filter concept
categoryBox.setConverter(displayNameConverter());
categoryBox.setValue(Category.OTHER);

amountField.setPromptText("24.90");
descriptionField.setPromptText("Weekly shop");
datePicker.setValue(LocalDate.now());
datePicker.setDayCellFactory(picker -> new DateCell() {
    @Override public void updateItem(LocalDate date, boolean empty) {
        super.updateItem(date, empty);
        setDisable(empty || date.isAfter(LocalDate.now()));
    }
});
```

Note the category combo has **no null entry**, unlike sprint 16's filter. "All
categories" means something when filtering and nothing when recording a purchase. The
same control type, two different item lists, because they answer different questions.

`setPromptText` is grey placeholder text that disappears on focus — it shows the expected
format without needing a separate hint label.

---

## Validation wiring

```java
private void wireValidation() {
    bindError(amountField.textProperty(),      amountError,      Validation::amount);
    bindError(descriptionField.textProperty(), descriptionError, Validation::description);

    datePicker.valueProperty().addListener((obs, old, now) ->
        show(dateError, Validation.date(now)));

    BooleanBinding invalid = Bindings.createBooleanBinding(
        this::hasErrors,
        amountField.textProperty(),
        descriptionField.textProperty(),
        datePicker.valueProperty(),
        categoryBox.valueProperty());

    dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(invalid);
}

private boolean hasErrors() {
    return !Validation.amount(amountField.getText()).isEmpty()
        || !Validation.description(descriptionField.getText()).isEmpty()
        || !Validation.date(datePicker.getValue()).isEmpty()
        || categoryBox.getValue() == null;
}

private static void show(Label label, List<String> errors) {
    label.setText(errors.isEmpty() ? "" : errors.get(0));
    label.setVisible(!errors.isEmpty());
    label.setManaged(!errors.isEmpty());
}
```

**Every rule here comes from `Validation`.** There is no regex in this file, no
`length() > 100`, no `isAfter(LocalDate.now())` outside the day cell factory. That is what
makes U-2 honest: the dialog and the domain cannot disagree, because there is one
implementation and the dialog reports where the domain throws.

`errors.get(0)` shows the first message. Sprint 03's validators can return more than one;
showing all of them next to a text field is more noise than help, and the first is always
the most specific.

### The order the wiring must happen in

`lookupButton` returns `null` until the button types are added to the dialog pane. Call
`getButtonTypes().addAll(...)` **before** `wireValidation()`, or you get a
`NullPointerException` that does not obviously point at the cause.

---

## Pre-filling for edit

```java
private ExpenseDialog(Window owner, Expense existing) {
    this.existing = existing;
    dialog.initOwner(owner);
    dialog.setTitle(existing == null ? "Add expense" : "Edit expense");

    buildLayout();
    if (existing != null) {
        amountField.setText(Money.format(existing.amount()));
        categoryBox.setValue(existing.category());
        descriptionField.setText(existing.description());
        datePicker.setValue(existing.date());
    }
    wireValidation();
}
```

`Money.format` for the amount, so the field shows `24.90` and re-parsing gives the same
value. `existing.amount().toString()` would usually work and would show `24.9` for a
scale-1 value that somehow got in.

Pre-fill **before** wiring validation, so the initial state is evaluated against the
filled fields and Save starts enabled for a valid existing expense.

## The result converter

```java
dialog.setResultConverter(button -> {
    if (button != ButtonType.OK) return null;

    BigDecimal amount = Money.parse(amountField.getText());

    return existing == null
        ? Expense.create(amount, categoryBox.getValue(), descriptionField.getText(), datePicker.getValue())
        : existing.edit(amount, categoryBox.getValue(), descriptionField.getText(), datePicker.getValue());
});
```

`edit` when editing. Sprint 04 made it preserve `id` and `createdAt` — using `create`
here would generate a new id, and sprint 10's `update` would then report
`ExpenseNotFoundException` for an expense that is plainly on the screen. A confusing bug,
and one line away.

Neither call can throw in practice, because Save is disabled unless everything validates.
If one does, it is the bug the specification names — *"a domain exception surfacing in the
UI is a bug in the dialog"* — and sprint 18's `ErrorDialogs` catches it as a last resort.

---

## Wiring into `MainView`

```java
private void onAdd() {
    ExpenseDialog.forNew(window()).ifPresent(expense ->
        runner.run(() -> { expenseService.add(expense.amount(), expense.category(),
                                              expense.description(), expense.date());
                           return null; },
                   ignored -> reload(),
                   this::showError));
}

private void onEdit(ExpenseRow row) {
    ExpenseDialog.forEditing(window(), row.source()).ifPresent(edited ->
        runner.run(() -> expenseService.update(edited), ignored -> reload(), this::showError));
}
```

The dialog runs on the FX thread; the **save** goes through `BackgroundRunner`. Sprint
15's rule, and the reason `showAndWait` blocking is fine: the database work is still off
the thread.

`row.source()` is sprint 14's escape hatch — the real `Expense` behind the displayed
strings.

On success: `reload()`. Not `rows.add(...)`. U-5.

### Double-click to edit

```java
table.setRowFactory(view -> {
    TableRow<ExpenseRow> row = new TableRow<>();
    row.setOnMouseClicked(event -> {
        if (event.getClickCount() == 2 && !row.isEmpty()) onEdit.accept(row.getItem());
    });
    return row;
});
```

`!row.isEmpty()` guards against double-clicking the blank area below the last row —
`TableRow` objects exist for empty space too, and `getItem()` would be `null`.

---

## `app.css`

```css
.field-error {
    -fx-text-fill: #d03a30;
    -fx-font-size: 11px;
}

.dialog-pane { -fx-padding: 0; }
```
