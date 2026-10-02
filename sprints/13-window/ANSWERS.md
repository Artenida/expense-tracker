# Sprint 13 · Reading check answers

Answers to the four questions at the end of `README.md`.

---

## 1. `init()` cannot touch the UI. What happens if you try, and how do you get an error from `init()` in front of the user?

### What happens

`init()` runs on the **JavaFX-Launcher** thread. The toolkit exists by then, but this is not the
FX Application Thread. Anything that needs the FX thread checks which thread it is on, and throws:

```
java.lang.IllegalStateException: Not on FX application thread; currentThread = JavaFX-Launcher
```

Creating a `Stage`, showing an `Alert`, or calling `showAndWait` all fail this way. The
exception then escapes `init()`, and `launch` gives up: it prints
`Exception in Application init method` with a stack trace to the console, and the process exits.
No window opens and no dialog appears. A user who started the app from the Dock sees it bounce
once and disappear, with the only explanation in a terminal they never opened.

There is one subtlety. Simply **constructing** most nodes off the FX thread is allowed, for
example `new Label(...)` or `new BorderPane()`. What is not allowed is touching a live scene
graph, and creating windows. So "cannot touch the UI" is a good rule, even if not every UI call
breaks it.

### Getting the error to the user

Do not show it from `init()`. Record it, and let `start()` show it:

```java
} catch (StoreException e) {
    startupFailure = e;              // init: remember it
}
...
if (startupFailure != null) {        // start: on the FX thread, dialogs work
    showFatalError(startupFailure);
    Platform.exit();
    return;
}
```

`start()` runs on the FX Application Thread, so a modal `Alert` works there. Returning before
`stage.show()` means the main window never appears, which is the requirement: a half-migrated
database never reaches an interactive UI.

The field is not `volatile` even though two threads use it. It is written on the launcher thread
and read on the FX thread. JavaFX's launcher waits for `init()` to finish before it schedules
`start()`, and that hand-off includes the synchronisation that makes the write visible.

## 2. Why does the `BorderPane`'s centre grow when you resize the window, while the left region does not?

### Because that is the `BorderPane`'s layout policy

Resizing the window resizes the `Scene`, and the `Scene` resizes its root, the `BorderPane`, to
fill it. The `BorderPane` then divides its space in a fixed order:

1. **top** and **bottom** get their preferred **height**, at full width. Here that is the status
   bar.
2. **left** and **right** get their preferred **width**, at whatever height remains. Here that is
   200 and 300 from `setPrefWidth`.
3. **centre** gets **everything left over**.

So at 1100 px wide the centre is 600 px, and at 1600 px it is 1100 px. The side regions are
asked for their preferred width every time, and the answer does not change. The centre is not
asked anything; it is given the remainder.

### The second condition: the child must be able to grow

The `BorderPane` offers the centre the leftover space, but the child only fills it if its maximum
size allows. The placeholder is a `StackPane`, and layout panes have an unbounded maximum
(`Double.MAX_VALUE`), so it fills the space. If a bare `Label` were put in the centre instead, it
would stay its own size and sit in the middle of the space, because a control's maximum size
defaults to its preferred size. "The centre grows" is really two things together: the
`BorderPane` offers the space, and the child accepts it.

The minimum window size (900×560) protects the other direction. Below 200 + 300 + some centre,
the regions would be squeezed until they overlapped.

## 3. `Launcher.main` calls `App.main`. Trace what happens from there to `start(Stage)` being called: which class actually calls it?

```
main thread
  Launcher.main(args)
    App.main(args)
      Application.launch(args)
        works out which class to launch: App, the class that called launch
        LauncherImpl.launchApplication(App.class, args)
          PlatformImpl.startup(...)        starts the toolkit and the FX Application Thread
          starts the "JavaFX-Launcher" thread, then the main thread waits

JavaFX-Launcher thread
  on the FX thread: new App()              the public no-arg constructor, by reflection
  app.init()                               on this launcher thread
  on the FX thread, waiting for it to finish:
      new Stage()                          the primary stage
      app.start(primaryStage)              <- called here
  waits until the toolkit shuts down
      app.stop()                           on the FX thread
```

**`com.sun.javafx.application.LauncherImpl` calls `start`**, JavaFX's internal launcher. It uses
`PlatformImpl.runAndWait` to run the call on the FX Application Thread. Nothing in our code calls
`start`, `init` or `stop`, and nothing calls `new App()`. JavaFX constructs the class itself,
which is why `App` must keep a public no-argument constructor.

Some points worth noticing:

- `launch(args)` with no class argument finds the class by **looking at who called it**. That
  works because `App.main` calls it. If `Launcher.main` called `Application.launch(args)`
  directly, JavaFX would try to launch `Launcher`, which does not extend `Application`, and would
  fail. That is why `Launcher` calls `App.main` and not `launch`.
- The main thread **blocks** inside `launch` for the whole life of the app. `Launcher.main`
  returns only after the toolkit has exited.
- Because the JVM's main class is `Launcher`, not a subclass of `Application`, JavaFX never runs
  the check that looks for its modules on the module path. What you get instead is the startup
  warning `Unsupported JavaFX configuration: classes were loaded from 'unnamed module'`. It is
  harmless, and sprint 20 deals with it properly.

## 4. The window is closed and the JVM keeps running. List three things that could be holding it open.

The JVM exits when the **last non-daemon thread** ends. After the window closes, whatever is
still running is one of these:

1. **A non-daemon thread pool.** From sprint 15, an `ExecutorService` built with the default
   thread factory keeps its worker threads alive after they finish their work, waiting for more.
   Each one is a non-daemon thread with nothing to do, and none of them will ever end. That is why
   sprint 15 gives the executor daemon threads **and** calls `shutdown()` in `stop()`. The same
   goes for a `java.util.Timer` created without `new Timer(true)`, or a `new Thread(...)` left
   running.
2. **The FX toolkit itself never exiting.** It shuts down when the last window closes, but only
   if `Platform.isImplicitExit()` is true. Calling `Platform.setImplicitExit(false)`, for example
   to keep a tray icon alive, means closing the window no longer ends anything. A window that was
   **hidden** rather than closed, or an owned `Stage` or dialog still open behind the main window,
   also counts as a window, so the toolkit keeps running.
3. **`stop()` that never returns.** The shutdown path runs `stop()` on the FX thread. If it
   blocks, for example on `executor.awaitTermination(Long.MAX_VALUE, ...)` while a task is stuck
   in a JDBC call that ignores interruption (sprint 09), shutdown never completes.
4. **Another toolkit's event thread.** Touching AWT or Swing, for example
   `java.awt.Desktop.browse(...)` or `java.awt.Toolkit`, starts AWT's own non-daemon event thread,
   and JavaFX knows nothing about it.

Today none of these exist, so `jps -l | grep expensetracker` should print nothing after closing
the window. Checking that now, while it passes, is what makes it a useful signal in sprint 15.
