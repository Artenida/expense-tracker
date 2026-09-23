# Sprint 13 · Tests

Mostly manual this sprint, and that is the honest answer rather than a gap.

---

## What is worth automating

| Test | File | Proves |
|---|---|---|
| `onlyUiImportsJavaFx` | `ArchitectureTest` | Still passes with the two documented exceptions |
| `onlyStoreImportsJavaSql` | `ArchitectureTest` | `MainView` holds services, not stores |
| `appCssIsOnTheClasspath` | `ResourcesTest` | `getResource("/app.css")` is not null |
| `migrationsAreOnTheClasspath` | `ResourcesTest` | Both `.sql` files resolve |

The resource tests look trivial and catch a real failure: a file in
`src/main/resources` that Maven did not copy, usually because it is in the wrong
directory. That failure otherwise appears as a `NullPointerException` at startup with no
indication of the cause.

```java
@Test
void appCssIsOnTheClasspath() {
    assertNotNull(getClass().getResource("/app.css"),
        "app.css must be in src/main/resources");
}
```

## What is not worth automating

Nothing in this sprint tests that the window *looks* right. That is deliberate, and it
follows the specification's own advice: *"UI tests are slow, brittle and sensitive to
layout changes you will make constantly."* You are about to change this layout in six
consecutive sprints.

Sprint 19 adds two or three TestFX smoke tests, tagged `ui` so they stay out of
`mvn clean test`. Before then, checking a window opens is faster by hand than by
framework.

## The manual checklist

Run through it now, and again at the end of every UI sprint.

### 1. It starts

```sh
mvn clean javafx:run
```

A window, 1100×700, titled "Expense Tracker", with four visible regions.

### 2. First run against nothing

```sh
rm -rf data/ && mvn javafx:run
```

The window appears, the status bar shows an absolute path and `schema v2`, and
`data/expenses.db` now exists. Check with the SQLite CLI:

```sh
sqlite3 data/expenses.db ".tables"        # expenses  budgets  schema_version
sqlite3 data/expenses.db "SELECT * FROM schema_version;"
```

### 3. Second run changes nothing

Run again. Still `schema v2`, still two rows in `schema_version`. Spec test 11, observed
rather than asserted.

### 4. A failing migration does not open a window

```sh
chmod 444 data/expenses.db && mvn javafx:run
```

Expect an `Alert` naming the file, and **no main window**. Then `chmod 644` to undo it.

This is the requirement about a half-migrated database never reaching an interactive UI.
It is worth doing once by hand, because it is the one startup path you will otherwise
never see.

### 5. It exits cleanly

The one people skip, and the one the specification calls out:

```sh
mvn javafx:run &
# close the window with the red button, then:
jps -l | grep expensetracker      # must print nothing
```

If a process survives, something non-daemon is alive. Today there is nothing it could
be — which is the point of checking now. From sprint 15 there will be, and you will know
the check was passing before.

`jps` ships with the JDK. `ps aux | grep java` works too.

### 6. Resizing

Drag the window wider and taller. The centre placeholder should grow; the left and right
should stay their preferred widths. Drag it small — it should stop at 900×560.

If the centre does not grow, you put something in the wrong `BorderPane` slot.

---

## Record what you observe

Keep a note of the startup time from `mvn javafx:run` to the window appearing. It will
be a second or two now. Sprint 15 seeds 50 000 rows, and knowing the before number is
the only way to tell whether a later slowdown is the database or the toolkit.
