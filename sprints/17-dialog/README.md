# Sprint 17 · The add/edit dialog

**Time:** ~3 hours · **Prerequisites:** 16 · **Produces:** `ExpenseDialog`, add and edit working end to end

---

## Purpose

One dialog, used for both adding and editing. It is where the specification's U-2 —
*"validation happens twice, deliberately"* — stops being an idea and becomes code.

The domain already rejects bad values by throwing. This dialog's job is to make sure the
user never reaches that throw: it validates as you type, disables Save while anything is
wrong, and puts each message next to the field it belongs to. Crucially it does **not**
re-implement any rule. It calls sprint 03's `Validation`, the same methods
`Expense`'s constructor calls, and merely reports where the domain would throw.

The specification's own summary: *"A domain exception surfacing in the UI is a bug in
the dialog, not a normal path."*

## New concepts

### 1. `Dialog<T>` and the result converter

```java
Dialog<Expense> dialog = new Dialog<>();
dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

dialog.setResultConverter(button ->
    button == ButtonType.OK ? buildExpense() : null);

Optional<Expense> result = dialog.showAndWait();
```

`Dialog<T>` is parameterised by what it produces. The result converter turns "which
button was pressed" into that value — `null` for Cancel, which is how `showAndWait`
knows to return `Optional.empty()`.

`showAndWait()` **blocks the FX thread** until the dialog closes. That is legitimate:
the thread is not doing work, it is running a nested event loop that keeps the rest of
the UI drawing. It is the one place blocking is correct, and it is worth understanding
why it is not a violation of sprint 15's rule.

### 2. A binding computes a value from other values

```java
BooleanBinding invalid = Bindings.createBooleanBinding(
    () -> !Validation.amount(amountField.getText()).isEmpty()
       || !Validation.description(descriptionField.getText()).isEmpty()
       || !Validation.date(datePicker.getValue()).isEmpty()
       || categoryBox.getValue() == null,
    amountField.textProperty(),
    descriptionField.textProperty(),
    datePicker.valueProperty(),
    categoryBox.valueProperty());

dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(invalid);
```

The lambda is the computation. The properties after it are the **dependencies** — when
any of them changes, the binding recomputes and anything bound to it updates.

Two failure modes, both silent:

- **A missing dependency.** Leave `datePicker.valueProperty()` out and the Save button
  will not re-enable when the user fixes the date. Nothing errors; the button just stops
  responding to one field.
- **A dependency that is not read in the lambda.** Harmless, just wasted recomputation.

This is the specification's *"Save button bound to a validity expression — disabled while
any field is invalid, so the user cannot submit garbage"*, and it is the single most
useful JavaFX idiom in the project.

### 3. `lookupButton` — reaching into the dialog pane

The OK button is created by the `DialogPane`, not by you, so you have to look it up:

```java
Node saveButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
```

Returns a `Node`. Cast to `Button` if you need button-specific methods; `disableProperty()`
is on `Node`, so no cast is needed here.

### 4. Per-field error labels

```java
Label amountError = new Label();
amountError.getStyleClass().add("field-error");
amountError.setVisible(false);
amountError.setManaged(false);

amountField.textProperty().addListener((obs, old, now) -> {
    List<String> errors = Validation.amount(now);
    amountError.setText(errors.isEmpty() ? "" : errors.get(0));
    amountError.setVisible(!errors.isEmpty());
    amountError.setManaged(!errors.isEmpty());
});
```

`setVisible(false)` hides a node but **keeps its space** — the layout jumps by a whole
line as messages appear and disappear. `setManaged(false)` removes it from the layout
calculation too. You almost always want both, and setting only `visible` is a common
cause of twitchy-looking dialogs.

The specification is explicit that this is per-field: *"Each invalid field shows its own
message next to it, not one generic error at the bottom."*

### 5. Disabling future dates in the `DatePicker`

```java
datePicker.setDayCellFactory(picker -> new DateCell() {
    @Override public void updateItem(LocalDate date, boolean empty) {
        super.updateItem(date, empty);
        setDisable(empty || date.isAfter(LocalDate.now()));
    }
});
```

A cell factory builds each day cell; overriding `updateItem` decides how it renders.
`super.updateItem(...)` **first**, always — skip it and cells recycle wrongly as you
scroll months, showing stale dates.

Cells are reused as you scroll, so `updateItem` is called repeatedly for the same cell
with different dates. That is why the disable decision has to be recomputed every time
rather than set once.

This only stops *clicking* a future date. `Validation.date` still runs, and
`Expense.create` still throws. Three layers for one rule, each one a different kind of
defence — the picker prevents, the validator reports, the domain enforces.

## Add and edit are one dialog

```java
public static Optional<Expense> forNew(Window owner);
public static Optional<Expense> forEditing(Window owner, Expense existing);
```

Same controls, same validation. The differences:

| | Add | Edit |
|---|---|---|
| Fields start | empty, date = today | filled from the expense |
| Title | "Add expense" | "Edit expense" |
| Produces | the four values | `existing.edit(...)` |

`existing.edit(...)` is sprint 04's method, which preserves `id` and `createdAt` and
re-validates. The dialog never constructs an `Expense` from scratch when editing, so it
cannot lose the identity.

## What you build

- `ui/ExpenseDialog`
- The `Add expense` toolbar button, enabled
- Double-click a row to edit
- Both calling `reload()` on success

## Definition of done

- [ ] Save is disabled until every field is valid
- [ ] Each invalid field shows its own message, and the layout does not jump
- [ ] Future dates cannot be picked
- [ ] A saved expense is still there after restarting the app
- [ ] Double-click pre-fills every field; untouched fields keep their values
- [ ] Editing keeps the id — check in the database, not just on screen
- [ ] No `ValidationException` ever reaches a dialog the user sees

---

## Reading check

1. `showAndWait()` blocks the FX thread. Why is that acceptable when sprint 15 forbids
   blocking it?
2. You forget `datePicker.valueProperty()` in the binding's dependency list. Describe
   exactly what the user experiences.
3. `setVisible(false)` versus `setManaged(false)` — what does the user see if you set
   only the first?
4. Edit uses `existing.edit(...)` rather than `Expense.create(...)`. What breaks if you
   use `create`, and when would you first notice?
