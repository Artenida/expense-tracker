# Sprint 19 · Reading check answers

Answers to the four questions at the end of `README.md`.

---

## 1. `task.cancel()` interrupts the thread. Why does that not stop an insert loop, and what does?

### An interrupt is a request, not a stop

`Thread.interrupt()` does not stop a thread. It sets a flag on it. Code only notices the
flag if it does one of two things:

- calls a method that **checks** it and throws `InterruptedException`, such as
  `Thread.sleep`, `Object.wait`, `BlockingQueue.take` or `Future.get`;
- **polls** it explicitly with `Thread.interrupted()` or `isInterrupted()`.

The insert loop does neither. It runs `PreparedStatement.executeUpdate()` row after row,
and sqlite-jdbc does that work in **native code** through JNI. A thread inside a native
SQLite call does not look at Java's interrupt flag, and the driver does not check it
between statements either. The flag stays set, and the loop keeps inserting until it runs
out of rows. Then it **commits**. So with an interrupt alone, Cancel does nothing, and the
import completes in full.

It can also do harm. If the interrupt lands while the thread is in something that does
check it, such as a lock wait or channel I/O inside a driver, it can surface as an odd
exception partway through a transaction. That is why the code avoids interrupting
altogether.

### What does stop it: cooperative polling

Sprint 09 gave `ExpenseStore.addAll` a `BooleanSupplier cancelled` parameter, and the
loop asks it **between rows**:

```
for each row:  if (cancelled.getAsBoolean()) { rollback; return 0; }   insert row
```

So the chain is: the button sets a flag, the loop sees it before the next row, the
transaction **rolls back**, and `addAll` returns 0. The store does not know about JavaFX.
It only knows "should I stop?".

### A detail the spec gets wrong

The spec passes `this::isCancelled` as that supplier and calls `task.cancel(false)` from
the button. That stops the loop, but `Task.cancel()` also moves the task to `CANCELLED`
**immediately**. `onCancelled` fires on the FX thread while `call()` is still running,
before the rollback has happened. I checked this with a small program: `onCancelled` ran
while `call()` was still inside its loop. So the spec's dialog would close, and say
"Nothing was imported", while the transaction was still open.

`ImportExportActions` therefore uses its **own** `AtomicBoolean` as the supplier, and
never cancels the `Task`. When the loop sees the flag it rolls back, `call()` returns 0
normally, and the task **succeeds** with 0. Only then does `onSucceeded` close the dialog.
A headless test confirmed the result: I imported 50,000 rows, cancelled at 33%, and 0 rows
were added.

## 2. `bar.progressProperty().bind(task.progressProperty())` — which thread updates the bar, and who arranged it?

**The FX Application Thread updates the bar, and `Task` arranged it.**

1. On the worker thread, the progress callback calls `updateProgress(done, total)`.
2. `updateProgress` does **not** write `progressProperty()` there. It stores the latest
   value in an `AtomicReference` and, if no update is already pending, posts a single
   `Platform.runLater`.
3. On the FX thread, that runnable reads the **latest** stored value and sets the task's
   `progress`, `workDone` and `totalWork` properties.
4. The binding sees `progressProperty()` change, on the FX thread, and sets the bar's
   `progress`. The bar repaints on the next frame.

So the binding itself contains no thread logic at all. It is safe because the property it
follows only ever changes on the FX thread. The same applies to `updateMessage` and the
status label.

Step 2 also coalesces updates. A 50,000-row import calls `updateProgress` 50,000 times,
but while one update is waiting on the FX queue, newer values just replace the stored one.
The FX thread might apply a few hundred of them, one per pulse, instead of being flooded
with 50,000 runnables. Calling `bar.setProgress(...)` from the worker would be wrong in two
ways: on the wrong thread, and with no coalescing.

The toolkit's part is the `runLater` queue and the thread that drains it, which is the same
answer as sprint 15's question 2.

## 3. You forget `setOnCancelled`. Describe exactly what the user sees after clicking Cancel.

This assumes the spec's design, in which the Cancel button calls `task.cancel(false)` and
the event filter consumes the click so that the dialog does not close by itself.

1. The user clicks **Cancel**. The event filter calls `task.cancel(false)` and consumes the
   event, so the dialog stays open, as designed.
2. The task moves to `CANCELLED` straight away. The worker's loop notices on its next row,
   rolls back and returns, but a cancelled task's result is ignored. `onSucceeded` and
   `onFailed` never fire, and nothing is set to handle `CANCELLED`.
3. **The progress dialog stays on screen for good.** The bar freezes at whatever percentage
   it reached, and the message still says "Importing…". Clicking Cancel again does
   nothing, because the task is already cancelled and the click is consumed again.
4. **The main window is unusable.** The dialog is `APPLICATION_MODAL`, so clicks on the
   main window are refused. There is no other window to switch to.
5. To the user, the application has hung: a stuck bar, a button that does nothing, and a
   window that cannot be used. In the spec's version the window's close button still works,
   because `setOnCloseRequest` cancels again but does not consume, so the dialog can be
   closed with it, if the user thinks to try. Nothing has actually gone wrong: the data
   rolled back correctly, and the worker is idle.

It is the most likely bug in the sprint because everything else works. Import succeeds,
failures report, and the bug only shows on the one path that is tried least often.

**In this code** that path cannot occur. Cancel never calls `task.cancel()`, so the task
never reaches `CANCELLED`. A cancelled import **succeeds with 0**, and the same
`onSucceeded` that handles every normal import closes the dialog. The window's close
control is consumed while the import runs, so it cancels rather than leaving an import
running out of sight, and it closes normally once the import has finished.

## 4. The progress dialog is `APPLICATION_MODAL`. Give one argument for making it modeless and one against.

### For modeless: a long import should not take the application hostage

On a large file the import can take long enough that blocking everything is a real cost.
The user might want to look at last month's spending, check the budget tab, or simply keep
reading the table while the import runs. Nothing about reading requires the import to
finish first. Background work with a small progress indicator in the status bar (as a mail
client syncs, or an IDE indexes) treats the user's time as more important than the
program's convenience. That fits the whole point of sprint 15, which moved slow work off
the UI thread **so that the UI stays usable**.

### Against modeless: the data would change underneath the user

The import is rewriting the data the window displays, and every interaction with that data
mid-import raises a question with no good answer:

- **What does the table show?** Readers see the last committed state, because of WAL, so
  for the whole import the window shows the old data. Then it jumps all at once on commit,
  or never jumps if the import is cancelled. Any totals the user reads in the meantime are
  about to be wrong.
- **Edits get stuck in a queue.** There is one worker thread. A delete or a budget change
  made during the import waits behind it, so the UI says "saving" for as long as the import
  takes, and the change lands *after* the import, on data the user never saw.
- **A second import, or an export, can start.** Export would write a file without the rows
  being imported. Two imports would queue up, and a cancel would only affect one of them.
- **Cancel becomes hard to find.** A modeless dialog can end up behind the main window, and
  then the only way to stop the import is a window the user cannot see.

Modal makes all of that impossible by construction, at the cost of a few seconds' wait. For
this application, a single user importing a file of personal expenses, that is the right
trade. The modeless version is worth building only together with a design for each of the
questions above, and that would be a feature in its own right.
