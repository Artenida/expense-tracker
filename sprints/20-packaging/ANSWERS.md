# Sprint 20 · Reading check answers

Answers to the four questions at the end of `README.md`.

---

## 1. `mvn javafx:run` works but `java -jar` fails with "JavaFX runtime components are missing". What is different about how the two start the JVM?

**Where JavaFX is loaded from.**

`mvn javafx:run` (the `javafx-maven-plugin`) builds the java command line itself. It puts
the JavaFX jars on the **module path**, adds them with `--add-modules`, and puts everything
else on the classpath:

```
java --module-path …/javafx-base-21.0.4-mac.jar:…/javafx-graphics-…:…/javafx-controls-…
     --add-modules javafx.base,javafx.graphics,javafx.controls
     -classpath target/classes:…/sqlite-jdbc-3.46.1.3.jar
     com.expensetracker.Launcher
```

So when the JVM starts, `javafx.graphics` is a **named module in the boot layer**.

`java -jar target/expense-tracker-1.0-SNAPSHOT.jar` has none of that. There is one jar, on
the **classpath**, and every class in it, JavaFX's included, belongs to the **unnamed
module**. The boot layer has no module called `javafx.graphics`.

If the main class extends `Application`, the `java` launcher checks for exactly that module
before calling `main`, and stops when it is missing. I reproduced it in this sprint by
shading with `mainClass = App`. Running the jar printed the error and exited immediately.
With `Launcher` it starts.

## 2. `Launcher` does not extend `Application` and that fixes it. Explain the mechanism, not just the recipe.

The check is in the **`java` launcher**, not in JavaFX. It happens before your `main` runs.

1. Before invoking `main`, the launcher (`sun.launcher.LauncherHelper`) loads and
   validates the main class.
2. If that class is a subclass of `javafx.application.Application`, the launcher treats it
   as a JavaFX application. It switches to a JavaFX-specific path (`LauncherHelper.FXHelper`)
   that will call `Application.launch` for you, which is why an `Application` subclass
   does not strictly need a `main` at all.
3. Part of that path is a check: **is `javafx.graphics` present as a named module in the
   boot layer?** If not, it prints "JavaFX runtime components are missing…" and exits.
   Your code never runs.

`Launcher` is an ordinary class with a `main`. At step 2 the launcher sees nothing special
and calls `Launcher.main` like any other program. The check at step 3 never happens. Then:

- `Launcher.main` calls `App.main`, which calls `Application.launch(App.class)`.
- `Application.launch` does not care where JavaFX came from. The toolkit classes are on
  the classpath, loading them works, and the native libraries are extracted and loaded as
  usual.
- JavaFX notices it was loaded from the unnamed module and logs the warning
  `Unsupported JavaFX configuration: classes were loaded from 'unnamed module'`. That is a
  warning, not an error. JavaFX officially supports the module path, but it runs fine from
  the classpath.

So `Launcher` does not make JavaFX modular. It **avoids the one launcher check** that
requires modularity. The JVM's main class is no longer an `Application`, so the launcher's
JavaFX path never starts. `LauncherTest` exists because removing this one class undoes
the fix, and the failure only appears at package time.

## 3. The bundle is read-only. Name two things besides the database that would break in an application that wrote next to its executable.

1. **Logs.** A `java.util.logging.FileHandler` writing `./logs/app.log`, or the JVM's own
   `hs_err_pid*.log` crash report, which is written to the **working directory**. Inside
   a launched `.app` that directory is usually `/`, which is not writable. Logging setup fails
   at startup, or, worse, the crash report that would explain a crash is never written.
2. **Settings and caches.** A `settings.properties` with the last window size or the last
   export folder, a thumbnail cache, a "recent files" list. Each one fails to save, and the
   app forgets everything between runs. Usually nothing errors, because the save failure is
   caught and logged to a file that also cannot be written.
3. **Exports with a default location.** "Save next to the program" fails, so the export
   goes nowhere.
4. **Auto-update.** Downloading a new version into the bundle to replace the old jar is
   blocked by the read-only bundle, and would break the code signature even if it were not.

Two macOS mechanisms make this worse than "it's read-only":

- **Code signing.** A signed bundle that has changed fails its signature check. Gatekeeper
  then reports the app as **damaged** and refuses to open it, so a write that succeeded would
  break every launch after it.
- **App Translocation.** A quarantined app opened straight from a download or a disk image is
  run from a randomised, read-only copy under `/private/var/folders/…`. "Next to the
  executable" is then a different temporary path on every launch, so even data that was
  written there would vanish.

That is why `Database.resolveDefault` puts the database in
`~/Library/Application Support/ExpenseTracker/`. That is the directory every Mac app owns
for exactly this purpose.

One related fact surprised me in this sprint. Java's `user.home` comes from the account's
password-database entry, **not** the `HOME` environment variable. Running the bundle with
`env -i HOME=/somewhere/else` still used `/Users/i7`. So the spec's note that `HOME` must
be kept for the database path is unnecessary on macOS.

## 4. The `.app` is 70 MB. What is in it, and what would `jlink` change?

### What is in it: measured, not 70 MB

The default `jpackage` build of this project produced a **190 MB** `.app`. For a
non-modular app, jpackage cannot tell which JDK modules are needed, so it bundles **all of
them**: 168 MB of runtime. The final build is **92 MB**, and the `.dmg` is **39 MB**
compressed:

| Part | Size | What it is |
|---|---|---|
| `Contents/runtime` | ~82 MB | A Java runtime cut down to 11 modules: `java.base`, `java.desktop`, `java.sql`, `jdk.jfr`, `jdk.unsupported` and their dependencies, plus `jdk.localedata` for English and Albanian only. Of this, 53 MB is the `lib/modules` image and 19 MB is the HotSpot VM itself (`libjvm`). |
| `Contents/app` | ~10 MB | The shaded jar: our 0.2 MB of code, the JavaFX classes and their macOS native libraries, and SQLite's macOS native library |
| `Contents/MacOS`, `Resources` | < 1 MB | jpackage's native launcher, `Info.plist`, the icon |

Three changes got it there:

- **`--add-modules`** with the list from `jdeps --print-module-deps`. jpackage runs `jlink`
  internally, so this is `jlink` already. Runtime went from 168 to 124 MB.
- **`--include-locales=en,sq`.** All of `jdk.localedata` is 43 MB. Keeping only the
  locales this Mac uses brought the runtime to 82 MB. The module itself has to stay:
  without it month names would fall back to the root locale, so the app would read
  differently from `mvn javafx:run`. To ship to other languages, add them to that list.
- **Dropping other platforms' SQLite natives.** `sqlite-jdbc` carries its native library for
  every OS: 21 MB for FreeBSD, Linux, Android, musl and Windows, compared with 2 MB for macOS.
  The jar is macOS-only anyway because of JavaFX's natives, so the shade filter keeps Mac's
  only. The jar went from 22.5 to 9.8 MB.

### What a fully modular `jlink` would change

It would bring JavaFX **into the runtime image** rather than shipping it in a jar on the
classpath. With JavaFX's `jmods` on the module path, `jlink` links `javafx.base`,
`javafx.graphics` and `javafx.controls` into `lib/modules` next to the JDK's own modules.
Then:

- **The "unnamed module" warning and the `Launcher` workaround go away.** JavaFX is a real
  named module in the boot layer, which is the configuration it officially supports. The
  launcher check from question 2 passes.
- **Only what is reachable is kept.** jlink resolves the module graph from the app's
  `module-info`, rather than from a module list maintained by hand, and can apply
  `--compress` across the whole image, JavaFX included.
- **Startup can be faster.** Classes come from the linked, indexed module image instead of
  a 10 MB jar scanned on the classpath.

The size would not change much, because there is little left to cut: the JVM and
`java.base` are the floor, at roughly 45 MB. The real cost is that the app and **every
dependency** must be modules. `sqlite-jdbc` is only an automatic module, and jlink refuses
automatic modules. So it would have to be wrapped as a real module, or kept on the class
path next to a modular image. That is the work the README says makes `jpackage` with a
non-modular app the pragmatic choice, and the measurements bear it out: by trimming the
runtime by hand, the non-modular build gets most of the size benefit without that work.
