# Sprint 19 · CSV in the UI

**Time:** ~2.5 hours · **Prerequisites:** 18 · **Produces:** import/export buttons, a progress dialog with a working Cancel, the TestFX smoke tests

---

## Purpose

The last feature sprint. Sprint 12 built the CSV reader, the writer and the transactional
import; this one puts a `FileChooser` and a progress bar in front of them.

It is also the first time you write a `Task` by hand rather than through
`BackgroundRunner.run`. Import needs to **report progress** and **be cancellable**, and
those are exactly the two things `Task` provides that a plain `Supplier` cannot express.

## New concepts

### 1. `FileChooser`

```java
FileChooser chooser = new FileChooser();
chooser.setTitle("Import expenses");
chooser.getExtensionFilters().add(
    new FileChooser.ExtensionFilter("CSV files", "*.csv"));
chooser.setInitialFileName("expenses-2026-09.csv");

File chosen = chooser.showOpenDialog(window);      // or showSaveDialog
```

Returns `null` if the user cancels — a JavaFX API that predates `Optional` and one of the
few places you must null-check. Wrap it immediately:

```java
Optional<Path> chosen = Optional.ofNullable(chooser.showOpenDialog(window)).map(File::toPath);
```

`showSaveDialog` handles the "file already exists, overwrite?" prompt for you, on every
platform, in the platform's own style. Do not add your own.

`setInitialFileName` for export: a suggested name with the month in it saves the user
typing and makes the exported files self-describing.

### 2. `Task`'s built-in progress and message

```java
Task<Integer> task = new Task<>() {
    @Override protected Integer call() {
        return importService.importCsv(path,
            done -> updateProgress(done, total),        // built into Task
            this::isCancelled);                          // built into Task
    }
};
```

`Task` already has `progressProperty()`, `messageProperty()` and `isCancelled()`. You
call `updateProgress` and `updateMessage` from the **background** thread and they are
marshalled to the FX thread for you — which is why binding a `ProgressBar` to
`task.progressProperty()` just works.

This is the payoff for sprint 09's signature. `ImportService.importCsv` takes an
`IntConsumer` and a `BooleanSupplier`; `Task` supplies both, and the store knows nothing
about JavaFX.

### 3. Cancellation is cooperative, and `Task.cancel()` is not enough

```java
cancelButton.setOnAction(e -> task.cancel());
```

`cancel()` sets a flag and, by default, interrupts the thread. **Neither stops JDBC.**
The insert loop keeps going until it next checks `isCancelled()` — which sprint 09 does,
between rows.

So the chain is: the button sets a flag, the loop notices, `addAll` rolls back and
returns 0. Without the polling in sprint 09 the button would do nothing visible, and the
import would complete anyway.

`task.cancel(false)` avoids the interrupt entirely. Worth considering: interrupting a
thread inside a JDBC call does nothing useful and can leave a driver in an odd state.

### 4. A modal progress dialog

```java
Dialog<Void> dialog = new Dialog<>();
dialog.initOwner(owner);
dialog.initModality(Modality.APPLICATION_MODAL);

ProgressBar bar = new ProgressBar();
bar.progressProperty().bind(task.progressProperty());

Label status = new Label();
status.textProperty().bind(task.messageProperty());

task.setOnSucceeded(e -> dialog.close());
task.setOnFailed(e ->    { dialog.close(); ErrorDialogs.show(task.getException()); });
task.setOnCancelled(e ->  dialog.close());
```

**Bind, do not set.** `bar.progressProperty().bind(task.progressProperty())` means the
bar follows the task with no update code at all. `bar.setProgress(...)` in a listener
would be the same thing written longhand, with a chance of being on the wrong thread.

`APPLICATION_MODAL` blocks interaction with the main window. That is right here: an
import is changing the data the window is displaying, and letting the user filter and
edit mid-import invites questions with no good answers.

**All three terminal handlers close the dialog.** Miss `setOnCancelled` and cancelling
leaves a modal dialog with nothing behind it — an application that looks completely
frozen. It is the single most likely bug in this sprint.

### 5. Indeterminate progress

A `ProgressBar` with a value of `-1` shows the animated barber-pole. Use it while the
file is being parsed, before the row count is known:

```java
updateMessage("Reading file…");
// progress stays at its initial -1
List<Expense> parsed = reader.read(path);
updateMessage("Importing " + parsed.size() + " expenses…");
```

Honest feedback. A bar sitting at 0% during a long parse looks stuck; a moving
indeterminate bar says "working, total unknown".

## Where TestFX earns its place

The specification is firm: *"Add TestFX and write **two or three smoke tests only**."*

Three, and no more:

1. The window opens against a fresh database.
2. Adding an expense through the dialog makes a row appear.
3. An invalid amount keeps the dialog open with Save disabled.

Tagged `ui` so `mvn clean test` stays green without a display.

The reasoning is worth taking seriously rather than treating as a limit to push against.
UI tests are slow, they break on layout changes you make constantly, and everything
interesting is reachable without them — you have 100-odd fast tests proving the logic. A
large TestFX suite is a well-trodden way to spend three evenings learning about test
infrastructure instead of about Java.

## What you build

- `ui/ImportExportActions` — the choosers and the progress dialog
- The three toolbar buttons, enabled at last
- Three TestFX smoke tests

## Definition of done

- [ ] Export writes a file that re-imports with no loss
- [ ] Export honours the current filter
- [ ] A malformed line reports **which line and why**, and imports nothing
- [ ] Cancel mid-import leaves the database unchanged
- [ ] The progress dialog closes on success, failure **and** cancellation
- [ ] The window is responsive after every one of those
- [ ] Three TestFX tests pass, and are excluded from `mvn clean test`

---

## Reading check

1. `task.cancel()` interrupts the thread. Why does that not stop an insert loop, and what
   does?
2. `bar.progressProperty().bind(task.progressProperty())` — which thread updates the bar,
   and who arranged it?
3. You forget `setOnCancelled`. Describe exactly what the user sees after clicking Cancel.
4. The progress dialog is `APPLICATION_MODAL`. Give one argument for making it modeless
   and one against.
