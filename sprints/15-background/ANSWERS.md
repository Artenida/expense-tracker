# Sprint 15 · Reading check answers

Answers to the four questions at the end of `README.md`.

---

## 1. `executor.submit(task)` returns immediately. Where is the result, and what is `task.getValue()` at the moment `submit` returns?

### `getValue()` is `null`

`submit` only puts the task in the executor's queue and returns. At that moment the worker may
not have started `call()`, and certainly has not finished it, so there is no result yet.
`getValue()` returns `null`. It does not block and it does not throw, which is what makes the
mistake easy to miss: code that reads it straight after `submit` gets an empty table rather
than an error.

### Where the result ends up, and when

When `call()` returns, the value is still on the worker thread. `Task` then hands it to the FX
thread with `Platform.runLater`, and only there is it written into the task's `value`
property. The state changes to `SUCCEEDED` and `onSucceeded` fires. So there are three moments:

1. `submit` returns: `getValue()` is `null`, and the state is `READY` or `SCHEDULED`.
2. `call()` returns on the worker: the value exists, but `getValue()` is **still** `null`,
   because the FX thread has not processed the hand-off yet.
3. On the FX thread: `value` is set, then `onSucceeded` runs. This is the first point where
   `getValue()` is reliable.

That is why `BackgroundRunner.run` reads `task.getValue()` inside `setOnSucceeded` and
nowhere else. The callback runs at exactly the moment the value becomes valid, and on the
thread that is allowed to put it in the table.

`submit` also returns a `Future`, which the runner ignores on purpose. Calling `get()` on it
from the FX thread would block until the query finished, which is the sprint 14 freeze again.

## 2. `setOnSucceeded` runs on the FX thread. Who arranged that — `Task`, the executor, or the FX toolkit?

### `Task` did

The executor knows nothing about JavaFX. It sees a `Runnable` (`Task` implements
`RunnableFuture`), runs it on its worker thread, and that is all. A plain `Runnable` submitted
to the same executor would run entirely on the worker, start to finish.

The marshalling is written into `Task`. While it runs on the worker, every state change goes
through the FX thread. The `SCHEDULED` and `RUNNING` transitions are posted before `call()`
starts, and when `call()` finishes, `Task` posts "set the value, set the state to
`SUCCEEDED`". If `call()` throws, it posts the exception and `FAILED` instead. Event handlers
fire when the state property changes, and the state property only changes on the FX thread.
So `onSucceeded` and `onFailed` run there.

### The toolkit's part

The toolkit provides the queue that `Platform.runLater` posts to, and the thread that drains
it. It decides nothing about `Task`. You can see the dependency directly: without a running
toolkit, a `Task` cannot even **start**. I checked this while writing the tests. A `Task`
submitted with no toolkit never reached `call()`, because posting the `SCHEDULED` state change
threw `IllegalStateException: Toolkit not initialized` first. That is why
`shutdownWaitsForRunningWork` is tagged `ui` even though `TESTS.md` lists it as needing no
toolkit. Only the two tests that submit no work run in the normal build.

## 3. The pool has one thread and it is a daemon. Describe what happens on close if it were non-daemon and `shutdown()` were missing.

### The window goes away and the process does not

Step by step, after the user closes the window:

1. The last window closes. With implicit exit on, the toolkit begins shutting down and calls
   `App.stop()` on the FX thread.
2. `stop()` does nothing, because `shutdown()` is missing. It returns.
3. The FX Application Thread ends, along with the toolkit's other threads.
4. The JVM checks whether any non-daemon threads are left, and finds `expense-worker`.
5. `expense-worker` is idle. A single-thread executor's worker that has nothing to do waits in
   `LinkedBlockingQueue.take()` for its next task. With no `shutdown()`, nothing will ever
   tell it to stop, and nothing will ever submit another task, because the code that would
   is gone.

The JVM waits for that thread forever.

### What the user sees

Nothing, which is the problem. The window has gone, so the app looks closed. Underneath:

- The Java process keeps running. `jps -l` still lists it, and `mvn javafx:run` never returns
  to the prompt.
- On macOS, a packaged app (sprint 20) keeps its Dock icon with the "running" dot, and opening
  it again can start a second copy.
- Anything the process holds stays held, such as file handles or a lock on the database file.

The only way out is to kill the process. This bug is easy to ship because every test passes
and every visible behaviour is correct. You only notice it by checking for the process after
closing.

### Why the real code has both

Each of the two lines would prevent this on its own. `shutdown()` ends the worker, and a
daemon thread does not keep the JVM alive. The code has both because they cover different
mistakes. If `stop()` throws before reaching `shutdown()`, the daemon flag still lets the JVM
exit. `shutdown()` is still the correct way to close, because it lets an in-flight write
finish, for up to two seconds, rather than the JVM cutting it off. A daemon thread is
abandoned at exit, even halfway through a transaction.

## 4. `runLatest` discards all but the newest result. Name a screen in a real application where discarding would be wrong.

### Anything where each request is a separate action, not a newer version of the same question

`runLatest` is right when every request asks the same question with newer inputs: "show me
this month", then "no, this month". Only the newest answer matters, and an older one is
simply out of date.

It is wrong wherever every request matters for its own sake. A concrete example:

**A messaging app's send button.** The user sends three messages quickly. Each send is a
request, and each result says whether that message was delivered, along with the id the
server gave it. With `runLatest`, only the third message's result is delivered. The first two
stay marked "sending..." forever. Worse, if the first one **failed**, the failure is thrown
away too. The user believes a message went out that never did.

The same problem shows up in:

- **Saving edits.** The first save fails validation and the second succeeds. Discarding the
  first result hides the fact that some edits were never saved.
- **A file upload list.** Each file has its own row and progress, and each result belongs to
  its own row. None of them is out of date.
- **Bank transfers or orders.** Every request moves real money. Each outcome, success or
  failure, has to reach the user.

### The rule of thumb

Use discarding only for **reads whose answer replaces the previous one**: search-as-you-type,
filters, autocomplete, a chart for the selected range. For **writes**, every result has to be
delivered, which is why `ExpenseService.add`, `update` and `delete` from sprint 17 onwards go
through `run`, not `runLatest`. Note also that `runLatest` only discards results. It does not
cancel the work, so a discarded write still happens. It just happens without the user ever
hearing about it, which is the worst of both.
