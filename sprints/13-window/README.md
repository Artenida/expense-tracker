# Sprint 13 · A window that opens

**Time:** ~2 hours · **Prerequisites:** 12 · **Produces:** `Launcher`, `App`, `MainView`, a status bar

---

## Purpose

Twelve sprints in, and the first window. That ordering is the specification's strongest
opinion and it is worth restating now that you are about to break the seal:

> *"It is tempting on a desktop project to build a window first because it is the visible
> part — that temptation is exactly what produces applications with SQL in button
> handlers."*

Everything the application can do already works and is tested. This sprint puts a frame
around it. The window will be almost empty — three placeholder regions and a status bar
— and that is the deliverable: a window that opens, connects to the right database, runs
its migrations before showing anything, and closes cleanly.

"Closes cleanly" is doing real work in that sentence. The most common bug on a first
JavaFX project is an application that disappears from the screen and leaves the JVM
running.

## New concepts

### 1. The `Application` lifecycle

```java
public class App extends Application {
    @Override public void init()  { }   // background thread, before the UI exists
    @Override public void start(Stage stage) { }   // FX Application Thread
    @Override public void stop()  { }   // FX Application Thread, on shutdown
}
```

| Method | Thread | Use it for |
|---|---|---|
| `init()` | the launcher thread — **not** the FX thread | Slow setup. **Cannot touch the UI.** |
| `start(Stage)` | FX Application Thread | Building and showing the window |
| `stop()` | FX Application Thread | Releasing resources — sprint 15's executor |

Migrations go in `init()`. That satisfies the specification's *"runs before the window is
shown"*, and it means a slow first-run migration does not block the toolkit starting up.

Because `init()` cannot show a dialog — the FX thread is not running yet — the pattern
is: catch the failure, **store it in a field**, and let `start()` decide what to show.

### 2. `Stage`, `Scene`, and the node graph

Three nested things, and the names are not self-explanatory:

```
Stage       the OS window — title bar, close button, size
 └ Scene    the root of one screenful of content
    └ Node  everything else: panes, controls, labels
```

A `Stage` shows one `Scene` at a time. A `Scene` has exactly one root `Node`. Every
control is a `Node`, and `Node`s nest — which makes the UI a **tree**, and makes
"add this to that" the only structural operation you need.

The theatre metaphor is where the names come from, and it is more confusing than
helpful. Read `Stage` as "window" and `Scene` as "the content of the window".

### 3. Layout panes do the arithmetic

You never set pixel coordinates. You choose a pane whose *policy* matches what you want:

| Pane | Policy |
|---|---|
| `BorderPane` | Five slots: top, bottom, left, right, centre. Centre takes the leftover space. |
| `VBox` / `HBox` | Children in a column or a row |
| `GridPane` | Rows and columns, like a table |
| `StackPane` | Children on top of each other |

The specification's window is a `BorderPane`: filters left, table centre, summary right,
status bar bottom. Nothing in top. The centre growing to fill the window is the
`BorderPane`'s behaviour, not something you configure.

### 4. Why `Launcher` exists

```java
public final class Launcher {
    public static void main(String[] args) { App.main(args); }
}
```

A class that does nothing but call another class's `main`. It looks pointless and it is
the fix for sprint 20's hardest problem.

When the JVM's main class **extends `Application`**, JavaFX checks that its modules are
on the module path and refuses to start with *"JavaFX runtime components are missing"* if
they are only on the classpath — which is where Maven puts them, and where `jpackage`
puts them. A main class that does *not* extend `Application` skips that check entirely.

It costs one file now and saves an afternoon in sprint 20. Point the `javafx-maven-plugin`
and every future launcher at `Launcher`.

### 5. `Platform.exit()` and daemon threads

Closing the window does not necessarily stop the JVM. JavaFX shuts down when the last
window closes *and* nothing else is keeping the process alive.

Two rules, both from the specification's definition of done:

- Call `Platform.exit()` to end the FX toolkit deliberately.
- From sprint 15, the `ExecutorService` gets **daemon** threads and is shut down in
  `stop()`. A non-daemon thread pool with nothing to do will hold the JVM open forever,
  and the window will be gone, so there is nothing to close.

You will not see this bug until sprint 15 creates the thread. The habit — check that the
process actually exits, every time — starts here, while there is nothing to blame.

## The architecture test needs an exception

`App` and `Launcher` live at `com/expensetracker/`, not under `ui/`, and `App` imports
`javafx.application.Application`. Sprint 04's `onlyUiImportsJavaFx` will fail.

**Do not weaken the rule.** Add a named exception for those two files:

```java
private static final Set<String> JAVAFX_ALLOWED_OUTSIDE_UI = Set.of("App.java", "Launcher.java");
```

The difference matters. A rule with one documented exception still tells you when
something new leaks; a rule relaxed to "javafx is allowed at the top level" stops telling
you anything.

## What you build

- `Launcher`, `App`
- `ui/MainView` — the `BorderPane` with placeholders
- `ui/StatusBar` — database path and schema version
- The `ArchitectureTest` exception

## Definition of done

- [ ] `mvn javafx:run` opens a window
- [ ] The status bar shows the **absolute** database path and `schema v2`
- [ ] Deleting `data/expenses.db` and rerunning recreates and migrates it before the
      window appears
- [ ] A migration failure shows an `Alert` and exits, rather than opening a broken window
- [ ] Closing the window exits the JVM — check with `jps` that no process is left
- [ ] `mvn clean test` is still green

---

## Reading check

1. `init()` cannot touch the UI. What happens if you try, and how do you get an error
   from `init()` in front of the user?
2. Why does the `BorderPane`'s centre grow when you resize the window, while the left
   region does not?
3. `Launcher.main` calls `App.main`. Trace what happens from there to `start(Stage)`
   being called — which class actually calls it?
4. The window is closed and the JVM keeps running. List three things that could be
   holding it open.
