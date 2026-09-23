# Sprint 15 · Tests

`src/test/java/com/expensetracker/ui/task/BackgroundRunnerTest.java`

Covers **spec test 13**.

---

## The toolkit problem, stated honestly

`Task`'s `setOnSucceeded` and `setOnFailed` fire on the FX Application Thread, which does
not exist unless the toolkit is running. So spec test 13 — *"a `Task` that throws invokes
`setOnFailed` and leaves the table's `ObservableList` untouched"* — cannot run as a plain
unit test.

Two honest options:

| Option | Cost |
|---|---|
| Start the toolkit once per JVM with `Platform.startup(...)` | A few hundred ms, needs a display session |
| Tag it `ui` and exclude it from `mvn clean test` | The test exists but does not run by default |

Do **both**: start the toolkit with a small JUnit extension, and tag the test `ui` so
`mvn clean test` stays green on a machine with no display. Run it deliberately with
`mvn test -DexcludedGroups=`.

```java
public final class FxToolkit implements BeforeAllCallback {

    private static boolean started = false;

    @Override public synchronized void beforeAll(ExtensionContext context)
            throws InterruptedException {
        if (started) return;
        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("toolkit did not start");
        Platform.setImplicitExit(false);     // keep it alive between test classes
        started = true;
    }
}
```

`Platform.startup` may be called **once per JVM** — a second call throws. Hence the
static flag. `setImplicitExit(false)` stops the toolkit shutting down when the last
(nonexistent) window closes, which would break every test class after the first.

## Waiting for the FX thread, without sleeping

Callbacks are asynchronous, so a test must wait. Never with `Thread.sleep` — it is either
too short (flaky) or too long (slow), and usually both on different machines.

```java
private static <T> T await(Consumer<Consumer<T>> action) throws Exception {
    CompletableFuture<T> future = new CompletableFuture<>();
    action.accept(future::complete);
    return future.get(5, TimeUnit.SECONDS);
}
```

The timeout is a failure mode, not a delay: a test that works takes milliseconds, and one
that hangs fails in five seconds with a clear message rather than blocking the build.

## Spec test 13

```java
@Test
@Tag("ui")
void aTaskThatThrowsInvokesOnFailedAndLeavesTheListUntouched() throws Exception {
    ObservableList<String> rows = FXCollections.observableArrayList("original");
    CompletableFuture<Throwable> failure = new CompletableFuture<>();

    runner.run(
        () -> { throw new StoreException("database is on fire"); },
        value  -> rows.setAll("should not happen"),
        error  -> failure.complete(error));

    Throwable caught = failure.get(5, TimeUnit.SECONDS);

    assertInstanceOf(StoreException.class, caught);
    assertEquals("database is on fire", caught.getMessage());
    assertEquals(List.of("original"), new ArrayList<>(rows));    // untouched
}
```

Both halves of the specification's requirement: `setOnFailed` fired **with the right
exception**, and `setOnSucceeded` did not, so the list is as it was.

Asserting the exception *type* matters. `Task` wraps nothing, so a `StoreException`
arrives as a `StoreException` — if it arrived as an `ExecutionException` you would know
the routing was wrong.

## Success and threading

| Test | Tag | Proves |
|---|---|---|
| `successDeliversTheValue` | `ui` | `onSuccess` gets exactly what the supplier returned |
| `workRunsOffTheFxThread` | `ui` | Inside the supplier, `Platform.isFxApplicationThread()` is **false** |
| `callbacksRunOnTheFxThread` | `ui` | Inside `onSuccess`, it is **true** |
| `errorsDoNotKillTheWorker` | `ui` | A failing task, then a succeeding one — the second still completes |

`workRunsOffTheFxThread` and `callbacksRunOnTheFxThread` are the two that actually
express the rule. Capture the boolean inside the lambda and assert on it afterwards:

```java
@Test @Tag("ui")
void workRunsOffTheFxThreadAndCallbacksRunOnIt() throws Exception {
    AtomicBoolean workOnFx     = new AtomicBoolean(true);
    AtomicBoolean callbackOnFx = new AtomicBoolean(false);
    CompletableFuture<Void> done = new CompletableFuture<>();

    runner.run(
        () -> { workOnFx.set(Platform.isFxApplicationThread()); return "x"; },
        v  -> { callbackOnFx.set(Platform.isFxApplicationThread()); done.complete(null); },
        e  -> done.completeExceptionally(e));

    done.get(5, TimeUnit.SECONDS);

    assertFalse(workOnFx.get(),     "work must not run on the FX thread");
    assertTrue(callbackOnFx.get(),  "callbacks must run on the FX thread");
}
```

`errorsDoNotKillTheWorker` is the one that would catch a genuinely bad implementation.
With a single-thread executor, an exception escaping into the pool would replace the
thread — or, if you had used `execute` instead of `submit` on a bare `Runnable`, print to
stderr and continue. Proving a second task still runs is proving the error handling did
not break the runner.

## `runLatest`

```java
@Test @Tag("ui")
void onlyTheNewestRequestDeliversItsResult() throws Exception {
    List<String> delivered = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch bothDone = new CountDownLatch(1);

    CountDownLatch holdFirst = new CountDownLatch(1);

    runner.runLatest(() -> { holdFirst.await(); return "slow"; }, delivered::add, e -> {});
    runner.runLatest(() -> "fast", v -> { delivered.add(v); bothDone.countDown(); }, e -> {});

    holdFirst.countDown();
    bothDone.await(5, TimeUnit.SECONDS);

    assertEquals(List.of("fast"), delivered);
}
```

Careful — with a **single-thread** executor the first task must finish before the second
starts, so the latch ordering here is what makes the scenario reproducible rather than
timing-dependent. Adjust to match your executor; the assertion is the invariant.

| Test | Proves |
|---|---|
| `onlyTheNewestRequestDeliversItsResult` | Stale results discarded |
| `aSingleRequestStillDelivers` | The guard does not block the normal case |
| `staleFailuresAreAlsoDiscarded` | `setOnFailed` is guarded too — otherwise a stale error pops a dialog |

## Shutdown

| Test | Tag | Proves |
|---|---|---|
| `shutdownStopsTheExecutor` | none | `isShutdown()` afterwards — no toolkit needed |
| `shutdownIsIdempotent` | none | Calling twice does not throw |
| `shutdownWaitsForRunningWork` | none | A task already running completes |

These three need no FX thread — they test the executor, not the callbacks — so leave them
untagged and let them run in every build.

---

## The manual checklist — the point of the sprint

### 1. 50 000 rows, responsive

Seed the fixture (sprint 09's `randomExpenses`) into your dev database, point the filter
at that month, and run.

- The window **appears immediately**, before the rows.
- While the query runs, you can **drag the window, resize it, click the toolbar**.
- The rows appear when the query finishes.

Compare with sprint 14, where the same thing froze. That is the deliverable.

### 2. It still exits

```sh
mvn javafx:run
# close the window
jps -l | grep expensetracker
```

Nothing. Now comment out `runner.shutdown()` in `App.stop()` and try again — it *still*
exits, because the threads are daemons. Then also remove `setDaemon(true)` and try
again: the process survives the window. That is the classic bug, reproduced on purpose,
in about two minutes.

Put both lines back.

### 3. Errors reach the handler

Temporarily `chmod 444` the database and try to load. A stack trace on the console — not
a frozen window, not a silent failure. Sprint 18 makes it a dialog.
