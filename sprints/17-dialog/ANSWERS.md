# Sprint 17 · Reading check answers

Answers to the four questions at the end of `README.md`.

---

## 1. `showAndWait()` blocks the FX thread. Why is that acceptable when sprint 15 forbids blocking it?

### It blocks the caller, not the thread

Sprint 15's rule is really about **event processing**: while your code is busy on the FX
thread, nothing is drawn and no input is handled. `showAndWait()` does not occupy the thread
like that. It starts a **nested event loop** (`Toolkit.enterNestedEventLoop`) on the same
thread and runs it until the dialog closes:

```
FX thread:  onAdd() → showAndWait() → [nested loop: repaint, mouse, keys, animations ...] → returns
```

Inside the nested loop the FX thread keeps doing its normal work. It repaints the main
window, runs the dialog's listeners and bindings, delivers `Task` callbacks, and handles
`Platform.runLater`. The only thing paused is the one method that called `showAndWait()`,
which cannot continue until there is a result. That is exactly the point.

A query is different. During `expenseService.find(...)` on the FX thread, the thread is
inside JDBC and cannot get back to the event loop at all. That is the freeze from sprint 14.

### Things you can see that confirm it

- The main window still repaints behind the dialog. Drag the dialog over it, or resize it.
- A `reload()` already in flight when the dialog opens still delivers its rows **while the
  dialog is open**, because `Task` callbacks are processed by the nested loop.
- The database work after Save still goes through `BackgroundRunner`. `onAdd` and `onEdit`
  run `expenses.add` and `expenses.update` on the worker. So the code waiting in
  `showAndWait` is waiting for the **user**, never for the disk.

Modality is a separate thing. The dialog is owned by the main window, which makes the main
window refuse input while the dialog is open. That is window behaviour, not a thread being
blocked.

## 2. You forget `datePicker.valueProperty()` in the binding's dependency list. Describe exactly what the user experiences.

### Save stays stuck in whatever state the last other change left it

`createBooleanBinding` recomputes `hasErrors()` only when one of the listed properties
changes. `hasErrors()` still **reads** the date, but a change to the date alone never
triggers a recompute. So:

1. The user opens Add, types `24.90` and `Weekly shop`. Save enables, because a recompute
   was triggered by the text fields and today's date is valid.
2. They type a future date into the picker's text field. Clicking a future day is blocked,
   but typing one is not. The **date's own error label appears** (`date cannot be in the
   future`), because that label has its own listener on `valueProperty()`. **Save stays
   enabled**, because the binding was not told anything changed.
3. They click Save. The converter calls `Expense.create(...)` with the future date, and the
   domain throws `ValidationException` from inside the result converter. This is the "domain
   exception surfacing in the UI" that the spec calls a bug in the dialog.

The opposite case also occurs:

1. The date starts out invalid, for example cleared, while the other fields are valid. A
   recompute triggered by the last text change has Save **disabled**.
2. The user fixes **only** the date. The red message disappears, and Save **stays
   disabled**.
3. Nothing they do with the date re-enables it. Typing a character into the amount and
   deleting it again works, because that triggers a recompute, and then Save enables.

So: the error label and the button disagree, and the button changes only when another
field is touched. Nothing is logged. `ExpenseDialogTest.fixingOnlyTheDateReenablesSave`
covers this. I checked by removing the dependency, and that test fails.

## 3. `setVisible(false)` versus `setManaged(false)` — what does the user see if you set only the first?

The two questions a layout asks about a node are:

- **`visible`**: is it **painted**?
- **`managed`**: does the parent **reserve space** for it, and position and resize it?

If only `visible` is toggled, the hidden error label stays managed. The `GridPane` keeps a
row for it, the height of an 11 px line plus the 6 px vgap, and that row is empty. What the
user sees depends on the starting state:

- **Labels hidden at start, with `managed` still true:** the dialog opens with a strange gap
  under Amount, under Description and under Date, as if it had been laid out for messages
  that are not there. It looks badly spaced, but it does not move.
- **Labels toggled with `visible` only, starting unmanaged** (the twitchy version): the
  first message to appear adds a row, and the dialog grows by a line.

A `Dialog` sizes its window to its content when shown. Afterwards, rows that appear and
disappear move everything **below** them. Typing `abc` into Amount and then deleting it makes
the Category, Description and Date fields jump down a line and back up on each keystroke that
changes validity. The buttons can be pushed past the bottom edge, and on some platforms the
window resizes too.

Setting both together, as `show(...)` does, gives the intended behaviour: no space is
reserved while there is no message, and the line only appears for the field that has one.
The default state, set by `errorLabel()`, is hidden **and** unmanaged, so the dialog opens
compact.

## 4. Edit uses `existing.edit(...)` rather than `Expense.create(...)`. What breaks if you use `create`, and when would you first notice?

### What breaks

`Expense.create` makes up a **new UUID** and stamps `createdAt = Instant.now()`. The edited
object then has the new values and the wrong identity. `onEdit` hands it to
`ExpenseService.update(edited)`, which runs `UPDATE expenses ... WHERE id = ?` with the
**new** id. No row matches. The store returns `false`, and the service throws
`ExpenseNotFoundException`.

So the edit is lost. The original row stays exactly as it was, and the user gets an error
saying that the expense they were just looking at does not exist. `createdAt` would also have
been reset, but the update never gets far enough for that to matter.

### When you would first notice

The **first time anyone edits anything**, but only as a stack trace on the console. Until
sprint 18, `showError` prints the trace and does nothing else. So what the developer actually
sees is:

1. They double-click a row, change the amount, and click Save.
2. The dialog closes. **The row still shows the old amount.** `reload()` never ran, because
   that happens on success, and this failed.
3. If the console is not in view, it looks as if Save did nothing.

That is quite a confusing first encounter: nothing visibly goes wrong, and the change
silently fails to stick. If it were "fixed" by having `update` fall back to `add` when
nothing matched, it would get worse. Every edit would leave the old row and add a new copy,
so expenses would be **duplicated**, and the totals would quietly grow with each edit.

`ExpenseDialogResultTest.editingKeepsTheIdAndCreatedAt` tests the converter's own choice
between `create` and `edit` (`ExpenseDialog.result`), not just `Expense.edit`, which sprint
04 already covers. I checked by swapping in `create`, and it and the "untouched fields" test
both fail.
