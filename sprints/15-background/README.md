# Sprint 15 · Off the UI thread

**Time:** ~3 hours · **Prerequisites:** 14 · **Produces:** `BackgroundRunner`, and the rule that makes the desktop version worth building

---

## Purpose

This is the sprint the whole project exists for. Everything up to sprint 12 you could
have learned from a console application. The specification is blunt about it: the
desktop UI *"forces you into the one thing a console app never teaches: keeping slow
work off the thread that draws the screen."*

At the end of sprint 14 you saw what breaking the rule looks like — a window that will
not repaint while a query runs. Now you fix it, once, in one class that every other
sprint uses.

## New concepts

### 1. There is exactly one thread that may touch the UI

JavaFX has a single **FX Application Thread**. It runs every event handler, every
animation frame and every repaint. Two rules follow, and they are absolute:

- **Never block it.** While your code runs on it, nothing is drawn and no input is
  handled. Anything over ~16 ms is a visible stutter; anything over a second looks
  broken.
- **Never touch a UI object from any other thread.** A `Label` updated from a background
  thread produces either nothing, a corrupted render, or an exception — non-deterministically.

Every UI toolkit works this way — Swing, Android, Qt, WinForms. JavaFX is unusual only
in making the rule explicit rather than letting you discover it.

### 2. `Task<T>` — the shape that satisfies both rules

```java
Task<List<Expense>> task = new Task<>() {
    @Override protected List<Expense> call() {
        return expenseService.find(filter);            // BACKGROUND thread
    }
};

task.setOnSucceeded(e -> rows.setAll(toRows(task.getValue())));   // FX thread
task.setOnFailed(e -> showError(task.getException()));            // FX thread

executor.submit(task);
```

`call()` runs on whatever thread the executor gives it. The handlers run on the **FX
Application Thread** — JavaFX marshals them for you. That is the whole design: the slow
part where it cannot block the UI, the UI part where it is legal.

`Task` implements `Runnable`, so an `ExecutorService` can run it directly.

Two mistakes that compile and are wrong:

- **Touching the UI inside `call()`.** It is a background thread. The compiler cannot
  stop you and the failure is intermittent.
- **Calling `task.getValue()` outside `setOnSucceeded`.** It is `null` until the task
  finishes. `submit(task)` returns immediately — that is the point.

### 3. You own the executor, and it must be shut down

```java
private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "expense-worker");
    thread.setDaemon(true);
    return thread;
});
```

Three decisions in four lines:

**Single thread.** The specification says own an executor; it does not say how big. One
is right here, for a reason specific to SQLite: every store method opens its own
connection, and two threads writing to the same file produce intermittent `SQLITE_BUSY`.
One worker serialises all database access, makes task ordering predictable, and is still
entirely sufficient — the UI thread is free, which is all the rule asks.

**Daemon threads.** A non-daemon thread keeps the JVM alive. Forget `shutdown()` and the
window closes while the process runs forever — the classic first-JavaFX bug the
specification warns about. Daemon threads make that impossible. You still call
`shutdown()`, because relying on daemon status to paper over a missing shutdown is
relying on a safety net rather than doing the thing.

**A named thread.** `"expense-worker"` instead of `pool-1-thread-1`. Costs nothing, and
the first time you look at a stack trace or a profiler you will be glad.

### 4. `Platform.runLater`

```java
Platform.runLater(() -> label.setText("done"));
```

Queues a `Runnable` for the FX thread. It is what `setOnSucceeded` uses internally.

You need it directly only when updating the UI from inside `call()` — which is mostly
what `updateProgress` and `updateMessage` exist for, and sprint 19 uses those instead.
If you find yourself writing `Platform.runLater` in a handler that is *already* on the
FX thread, something is confused.

### 5. Wrapping it up so the rule is followed by default

The specification says every service call goes through a `Task`. Written out at each
call site that is eight lines of ceremony, and the one time you skip it is the one that
breaks. So write it once:

```java
public <T> void run(Supplier<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError)
```

Call sites become:

```java
runner.run(() -> expenseService.find(filter),
           found -> tableView.setRows(found),
           error -> ErrorDialogs.show(error));
```

One line, correct by construction. Centralising it means there is exactly one place
where the threading can be wrong — and it is covered by tests.

## Stale results

Not in the specification, and it will happen from sprint 16. Change the month twice
quickly: task A (slow) and task B (fast) both run, B's result lands first, then A's
overwrites the table with the **older** month's rows.

`BackgroundRunner` gets a variant that carries a sequence number, and results from
anything but the latest request are discarded. Sprint 16 uses it for `reload()`. Three
lines, and without them you get a bug that reproduces about one time in ten.

## What you build

- `ui/task/BackgroundRunner` — the executor, `run`, and `runLatest`
- `App.stop()` — the shutdown
- `MainView` — the sprint 14 `TODO` removed

## Definition of done

- [ ] The window stays responsive while a query over 50 000 rows runs
- [ ] `App.stop()` shuts the executor down and the JVM exits
- [ ] A service call that throws reaches `setOnFailed` and leaves the table unchanged
- [ ] No service call is made from the FX thread anywhere
- [ ] The sprint 14 `TODO(sprint-15)` comment is gone

---

## Reading check

1. `executor.submit(task)` returns immediately. Where is the result, and what is
   `task.getValue()` at the moment `submit` returns?
2. `setOnSucceeded` runs on the FX thread. Who arranged that — `Task`, the executor, or
   the FX toolkit?
3. The pool has one thread and it is a daemon. Describe what happens on close if it were
   non-daemon and `shutdown()` were missing.
4. `runLatest` discards all but the newest result. Name a screen in a real application
   where discarding would be wrong.
