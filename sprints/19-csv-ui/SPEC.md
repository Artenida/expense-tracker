# Sprint 19 · Build specification

---

## `ui/ImportExportActions.java`

```java
public final class ImportExportActions {

    private final ImportService imports;
    private final BackgroundRunner runner;
    private final Window owner;
    private final Runnable onChange;          // MainView::reload

    public void exportCsv(ExpenseFilter filter);
    public void importCsv();
}
```

---

## Export

```java
public void exportCsv(ExpenseFilter filter) {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Export expenses");
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
    chooser.setInitialFileName("expenses-" + filter.month() + ".csv");

    File chosen = chooser.showSaveDialog(owner);
    if (chosen == null) return;

    runner.run(() -> { imports.exportCsv(chosen.toPath(), filter); return null; },
               ignored -> { /* nothing to refresh */ },
               ErrorDialogs::show);
}
```

`filter.month()` gives `2026-09` from `YearMonth.toString()`, so the suggested name is
`expenses-2026-09.csv`.

The filter is passed in, so export writes **what the user is looking at**. Exporting the
whole database from a toolbar sitting above a filtered table would surprise people.

No progress dialog. Export of a month is instant; adding a dialog that flashes and
vanishes is worse than none. If you later export the whole database, revisit it.

---

## Import

The one hand-written `Task` in the project.

```java
public void importCsv() {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Import expenses");
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));

    File chosen = chooser.showOpenDialog(owner);
    if (chosen == null) return;

    Path path = chosen.toPath();

    Task<Integer> task = new Task<>() {
        @Override protected Integer call() {
            updateMessage("Reading " + path.getFileName() + "…");
            return imports.importCsv(path,
                done -> updateProgress(done, expectedRows(path)),
                this::isCancelled);
        }
    };

    Dialog<Void> progress = buildProgressDialog(task);

    task.setOnSucceeded(e -> {
        progress.close();
        onChange.run();
        showImported(task.getValue());
    });
    task.setOnFailed(e -> {
        progress.close();
        ErrorDialogs.show(task.getException());
    });
    task.setOnCancelled(e -> {
        progress.close();
        new Alert(Alert.AlertType.INFORMATION,
                  "Import cancelled. Nothing was imported.").showAndWait();
    });

    runner.submit(task);
    progress.show();                // show() — not showAndWait()
}
```

### `show()` versus `showAndWait()`

`showAndWait()` would block the FX thread until the dialog closes — and the dialog is
closed by the task's handlers, which run **on** the FX thread. Deadlock.

`show()` returns immediately; the dialog stays up because it is modal; the handlers close
it when the task finishes. Sprint 17's dialog uses `showAndWait` because *it* is what
produces the result. Two dialogs, two lifetimes, and the difference matters.

### `expectedRows`

```java
private static long expectedRows(Path path) {
    try (Stream<String> lines = Files.lines(path)) {
        return Math.max(1, lines.count() - 1);        // minus the header
    } catch (IOException e) {
        return -1;                                     // indeterminate
    }
}
```

An extra pass over the file to get a denominator for the progress bar. On a file small
enough to import this is cheap, and returning `-1` on failure gives an indeterminate bar
rather than a crash. Counting first and importing second is a trade of a little I/O for
honest feedback.

### The progress dialog

```java
private Dialog<Void> buildProgressDialog(Task<Integer> task) {
    Dialog<Void> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.initModality(Modality.APPLICATION_MODAL);
    dialog.setTitle("Importing");

    ProgressBar bar = new ProgressBar();
    bar.setPrefWidth(320);
    bar.progressProperty().bind(task.progressProperty());

    Label status = new Label();
    status.textProperty().bind(task.messageProperty());

    VBox content = new VBox(10, status, bar);
    content.setPadding(new Insets(16));
    dialog.getDialogPane().setContent(content);
    dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

    Button cancel = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
    cancel.addEventFilter(ActionEvent.ACTION, event -> {
        task.cancel(false);
        event.consume();                 // keep the dialog open until onCancelled closes it
    });

    dialog.setOnCloseRequest(event -> task.cancel(false));
    return dialog;
}
```

Two subtleties:

- **`addEventFilter` + `event.consume()`.** A dialog's Cancel button normally closes the
  dialog immediately. Consuming the event stops that, so the dialog stays up until
  `setOnCancelled` closes it — which is *after* the rollback finishes. Otherwise the
  dialog vanishes while the transaction is still rolling back and the UI looks done when
  it is not.
- **`cancel(false)`** — do not interrupt. The interrupt would not stop the JDBC call
  anyway (sprint 09's polling does that) and could leave the driver in an odd state.

`setOnCloseRequest` treats closing the dialog with the window control as a cancel.
Without it, a user can dismiss the dialog and leave the import running invisibly.

### Reporting the result

```java
private void showImported(int count) {
    new Alert(Alert.AlertType.INFORMATION,
              count + (count == 1 ? " expense" : " expenses") + " imported.").showAndWait();
}
```

---

## Wiring the toolbar

Sprint 14's three disabled buttons, at last:

```java
add.setOnAction(e -> onAdd());
add.setDisable(false);

importButton.setOnAction(e -> actions.importCsv());
importButton.setDisable(false);

exportButton.setOnAction(e -> actions.exportCsv(filterPanel.currentFilter()));
exportButton.setDisable(false);
```

Delete the `// sprint 17` and `// sprint 19` comments as you go.

---

## TestFX

```xml
<dependency>
  <groupId>org.testfx</groupId>
  <artifactId>testfx-junit5</artifactId>
  <version>4.0.18</version>
  <scope>test</scope>
</dependency>
```

```java
@Tag("ui")
class SmokeTest extends ApplicationTest {

    @TempDir static Path tempDir;

    @BeforeAll
    static void pointAtATemporaryDatabase() {
        System.setProperty("expenses.db", tempDir.resolve("smoke.db").toString());
    }

    @AfterAll
    static void clearIt() {
        System.clearProperty("expenses.db");
    }

    @Override public void start(Stage stage) throws Exception {
        new App().start(stage);
    }
}
```

The `expenses.db` system property from sprint 06 is what makes this safe — the smoke
tests run against a `@TempDir` file and cannot touch your real data. That branch was
written twelve sprints ago for exactly this.

### The three tests

```java
@Test
void theWindowOpensAgainstAFreshDatabase() {
    verifyThat(".table-view", isVisible());
    verifyThat(".status-bar", isVisible());
}

@Test
void addingAnExpenseThroughTheDialogMakesARowAppear() {
    clickOn("Add expense");
    clickOn(".text-field").write("24.90");            // amount
    clickOn("#descriptionField").write("smoke test");
    clickOn("OK");

    verifyThat(".table-row-cell", hasItems(1));
}

@Test
void anInvalidAmountKeepsTheDialogOpenWithSaveDisabled() {
    clickOn("Add expense");
    clickOn("#amountField").write("abc");

    verifyThat("OK", Node::isDisabled);
    verifyThat(".dialog-pane", isVisible());          // still open
}
```

Give the controls `setId("amountField")` and so on. Looking them up by CSS class breaks
the moment you add a second `TextField`; an id is stable and says what it means.

**Stop at three.** The temptation to add a fourth is the trap the specification is
warning about.
