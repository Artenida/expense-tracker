# Sprint 15 · Build specification

---

## `ui/task/BackgroundRunner.java`

```java
package com.expensetracker.ui.task;

public final class BackgroundRunner {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "expense-worker");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong latestRequest = new AtomicLong();

    public <T> void run(Supplier<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError);
    public <T> void runLatest(Supplier<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError);
    public void     submit(Task<?> task);
    public void     shutdown();
}
```

### `run`

```java
public <T> void run(Supplier<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
    Task<T> task = new Task<>() {
        @Override protected T call() {
            return work.get();          // background thread
        }
    };
    task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));   // FX thread
    task.setOnFailed(e -> onError.accept(task.getException()));    // FX thread
    executor.submit(task);
}
```

The whole rule, in ten lines. `Supplier<T>` is the work; the two `Consumer`s are what to
do with the outcome.

`task.getException()` returns the `Throwable` that escaped `call()` — the
`StoreException`, `ValidationException` or `ExpenseNotFoundException` from the service.
`Task` catches everything and routes it here; nothing propagates out of `call()` into the
executor, which is why a failed task does not kill the worker thread.

### `runLatest`

```java
public <T> void runLatest(Supplier<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
    long request = latestRequest.incrementAndGet();

    Task<T> task = new Task<>() {
        @Override protected T call() { return work.get(); }
    };
    task.setOnSucceeded(e -> {
        if (request == latestRequest.get()) onSuccess.accept(task.getValue());
    });
    task.setOnFailed(e -> {
        if (request == latestRequest.get()) onError.accept(task.getException());
    });
    executor.submit(task);
}
```

`AtomicLong` because `incrementAndGet` happens on the FX thread but the comparison could
in principle be reached from elsewhere; atomicity costs nothing and removes the question.

The work still runs to completion — this discards the **result**, it does not cancel.
Proper cancellation needs `task.cancel()` and cooperative checks inside `call()`, which
is what sprint 19's import does. For a read that is about to be superseded, discarding is
simpler and enough.

Note the stale task still occupied the single worker thread. With one thread the
superseded query delays the newer one, which is a real cost and an acceptable one at
this scale. Worth knowing you made the trade.

### `submit` and `shutdown`

```java
/** For a Task built elsewhere — sprint 19's import, which needs progress and cancel. */
public void submit(Task<?> task) { executor.submit(task); }

public void shutdown() {
    executor.shutdown();
    try {
        if (!executor.awaitTermination(2, TimeUnit.SECONDS)) executor.shutdownNow();
    } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();     // restore the flag — never swallow it
    }
}
```

`shutdown()` stops accepting work and lets running tasks finish. `shutdownNow()`
interrupts them. Two seconds is enough for any query here and short enough that closing
the window feels immediate.

`Thread.currentThread().interrupt()` in the catch restores the interrupt flag that
catching `InterruptedException` cleared. Swallowing it silently breaks cancellation for
everything further up the stack. It is a small, very commonly missed detail.

---

## `App` — wiring the runner

```java
private BackgroundRunner runner;

@Override
public void start(Stage stage) {
    ...
    runner = new BackgroundRunner();
    MainView view = new MainView(expenseService, budgetService, summaryService,
                                 importService, runner,
                                 database.path(), migrator.currentVersion());
    ...
}

@Override
public void stop() {
    if (runner != null) runner.shutdown();
}
```

The runner is created in `start`, not `init` — it belongs to the UI, and `init` may never
reach `start` if a migration fails.

The `if (runner != null)` matters: `stop()` is called even when `start()` bailed out
early on a startup failure.

---

## `MainView` — the TODO goes away

Before:

```java
// TODO(sprint-15): this blocks the FX Application Thread.
tableView.setRows(expenseService.find(filter));
```

After:

```java
private void reload() {
    ExpenseFilter filter = currentFilter();
    runner.runLatest(
        () -> expenseService.find(filter),
        tableView::setRows,
        this::showError);
}
```

`runLatest`, not `run`, because from sprint 16 this is driven by combo boxes a user can
change faster than the query returns.

`currentFilter()` is evaluated **on the FX thread, before** the lambda is submitted. The
lambda captures the resulting `ExpenseFilter`, not the controls. Reading a `ComboBox`
from inside `call()` would be touching the UI from a background thread — one of the two
absolute rules, broken in a way that usually works.

That is subtle enough to be worth a comment in the code.

### A temporary error handler

```java
private void showError(Throwable error) {
    // TODO(sprint-18): replace with ErrorDialogs — a readable sentence, not a trace
    error.printStackTrace();
}
```

Sprint 18 builds the real one. A stack trace on the console is an honest placeholder;
swallowing the error silently is not.

---

## Verifying the rule holds

Add this to `BackgroundRunner` while you are working, then delete it:

```java
private static void assertNotOnFxThread() {
    if (Platform.isFxApplicationThread()) {
        throw new IllegalStateException("service call on the FX Application Thread");
    }
}
```

Call it as the first line of every `call()`. It turns "I think this is right" into a
loud failure, and it is the only way to be sure you have not left one synchronous call
behind.

A production version of this idea belongs in the *service* layer — a check in every
public service method that throws if `Platform.isFxApplicationThread()`. It would need
`service` to import `javafx.application.Platform`, which sprint 04's guard test forbids,
so it cannot live there in this project. Worth noticing: the architecture rule and the
runtime check are after the same thing from opposite directions, and here the
architecture rule wins.
