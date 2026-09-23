# Sprint 13 · Build specification

---

## `Launcher.java`

`src/main/java/com/expensetracker/Launcher.java`

```java
package com.expensetracker;

/**
 * Entry point. Deliberately does NOT extend Application — see sprint 20.
 * A main class that extends Application makes JavaFX check for its own modules
 * on the module path and refuse to start when they are on the classpath.
 */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        App.main(args);
    }
}
```

Write that comment. In six months it will look like a class that can safely be deleted.

---

## `App.java`

```java
public class App extends Application {

    private Database        database;
    private SchemaMigrator  migrator;
    private ExpenseService  expenseService;
    private BudgetService   budgetService;
    private SummaryService  summaryService;
    private ImportService   importService;

    private StoreException startupFailure;   // set in init(), read in start()

    public static void main(String[] args) { launch(args); }
}
```

### `init()` — wiring and migrations

```java
@Override
public void init() {
    try {
        database = Database.resolveDefault();
        migrator = new SchemaMigrator(database);
        migrator.migrate();

        ExpenseStore expenses = new JdbcExpenseStore(database);
        BudgetStore  budgets  = new JdbcBudgetStore(database);

        expenseService = new ExpenseService(expenses);
        budgetService  = new BudgetService(budgets);
        summaryService = new SummaryService(expenses, budgets);
        importService  = new ImportService(expenses, new CsvReader(), new CsvWriter());

    } catch (StoreException e) {
        startupFailure = e;          // cannot show a dialog from here
    }
}
```

**This is the only place in the application that knows every layer.** Stores are
constructed, services are handed the stores, and nothing else ever calls a constructor
from another package. Sprint 10's dependency injection, done by hand, in ten lines.

Read the direction: `JdbcExpenseStore` needs a `Database`; `ExpenseService` needs an
`ExpenseStore` (the interface); `SummaryService` needs both stores. Nothing points
upwards.

Keep `App` this thin. The specification is explicit: *"If it starts holding logic, that
logic belongs in a service."*

### `start(Stage)`

```java
@Override
public void start(Stage stage) {
    if (startupFailure != null) {
        showFatalError(startupFailure);
        Platform.exit();
        return;
    }

    MainView view = new MainView(expenseService, budgetService,
                                 summaryService, importService,
                                 database.path(), migrator.currentVersion());

    Scene scene = new Scene(view.getRoot(), 1100, 700);
    scene.getStylesheets().add(
        Objects.requireNonNull(getClass().getResource("/app.css")).toExternalForm());

    stage.setTitle("Expense Tracker");
    stage.setScene(scene);
    stage.setMinWidth(900);
    stage.setMinHeight(560);
    stage.show();
}
```

`showFatalError` is a modal `Alert` naming the file and the reason — the specification's
*"a half-migrated database that reaches an interactive UI is much worse than one that
refuses to start."*

`Objects.requireNonNull` around `getResource`: it returns `null` when the resource is
missing, and a `null` stylesheet fails later with a message that does not mention CSS.
Failing here says exactly what is wrong.

`setMinWidth`/`setMinHeight` stop a user dragging the window down to a size where the
three regions overlap into nonsense.

### `stop()`

```java
@Override
public void stop() {
    // sprint 15 shuts the executor down here
}
```

Leave the method with that comment. It is the hook you will need, and an empty override
with a note is better than remembering to add one later.

---

## `ui/MainView.java`

```java
public final class MainView {

    private final BorderPane root = new BorderPane();

    public MainView(ExpenseService expenses, BudgetService budgets,
                    SummaryService summaries, ImportService imports,
                    Path databasePath, int schemaVersion) {

        root.setLeft(buildFilterPanel());       // placeholder in this sprint
        root.setCenter(buildTablePlaceholder());
        root.setRight(buildSummaryPlaceholder());
        root.setBottom(new StatusBar(databasePath, schemaVersion).getRoot());
    }

    public Parent getRoot() { return root; }
}
```

The services arrive through the constructor and are stored in fields. They are not used
yet — sprints 14 to 19 fill in each region.

`getRoot()` returns `Parent`, not `BorderPane`. The caller needs something to put in a
`Scene`; it does not need to know the layout, and narrowing the return type stops `App`
reaching in and rearranging the window.

### The placeholders

```java
private Node buildTablePlaceholder() {
    Label label = new Label("Expenses will appear here");
    label.getStyleClass().add("placeholder");
    StackPane pane = new StackPane(label);
    pane.setPadding(new Insets(16));
    return pane;
}
```

`getStyleClass().add("placeholder")` rather than `setStyle("-fx-text-fill: grey")`. The
specification's rule — *"styling lives in `app.css`, not in `setStyle(...)` calls
scattered through view classes"* — starts being followed now, on a placeholder, because
a rule you start following at the end is a rule you have already broken.

Make the left region about 200 px wide and the right about 300, with
`setPrefWidth(...)`, so the layout looks like the real thing.

---

## `ui/StatusBar.java`

```java
public final class StatusBar {

    private final HBox root = new HBox(12);

    public StatusBar(Path databasePath, int schemaVersion) {
        Label db      = new Label(databasePath.toString());
        Label version = new Label("schema v" + schemaVersion);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        root.getChildren().addAll(db, spacer, version);
        root.getStyleClass().add("status-bar");
        root.setPadding(new Insets(6, 12, 6, 12));
    }

    public Node getRoot() { return root; }
}
```

The `Region` spacer with `Hgrow.ALWAYS` is the standard JavaFX idiom for "push these
apart": an invisible child that absorbs all the leftover width. There is no
`justify-content: space-between`; this is the equivalent.

`databasePath` is absolute — sprint 06's `toAbsolutePath()`. Showing `data/expenses.db`
would leave the user guessing which working directory that is relative to.

---

## `src/main/resources/app.css`

Start it now, so it exists and is wired up:

```css
.root {
    -fx-font-family: "SF Pro Text", "Helvetica Neue", sans-serif;
    -fx-font-size: 13px;
}

.status-bar {
    -fx-background-color: #f4f4f6;
    -fx-border-color: transparent transparent transparent transparent;
    -fx-border-width: 1 0 0 0;
    -fx-border-color: #dcdce0;
}

.status-bar .label { -fx-text-fill: #666; -fx-font-size: 11px; }

.placeholder { -fx-text-fill: #999; -fx-font-style: italic; }
```

JavaFX CSS looks like web CSS and is not. Every property is prefixed `-fx-`, the
selectors work on Java class names (`.label` matches every `Label`), and the supported
property set is much smaller. When something has no effect, the property probably does
not exist — JavaFX ignores unknown properties silently.

---

## `ArchitectureTest` — the documented exception

```java
private static final Set<String> JAVAFX_ALLOWED_OUTSIDE_UI =
    Set.of("App.java", "Launcher.java");

@Test
void onlyUiImportsJavaFx() throws IOException {
    // ... existing walk, plus:
    .filter(p -> !JAVAFX_ALLOWED_OUTSIDE_UI.contains(p.getFileName().toString()))
    // ...
}
```

Two names, listed explicitly. Adding a third means writing it down, which means noticing
you are doing it.
