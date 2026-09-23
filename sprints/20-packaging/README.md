# Sprint 20 · Packaging

**Time:** ~2 hours · **Prerequisites:** 19 · **Produces:** a `.app` and `.dmg` someone without a JDK can run

---

## Purpose

The step most side projects skip, and *"it only runs from my IDE"* is a real gap when you
show the project to someone.

It is also where two decisions made much earlier pay off, and where you find out whether
they were made correctly:

- **Sprint 13's `Launcher`** — the class that does nothing but call `App.main`.
- **Sprint 06's database path resolution** — the user-data directory branch.

Both were written with a comment saying "sprint 20 explains this". This is that sprint.

## New concepts

### 1. The "JavaFX runtime components are missing" error

You will probably see it. It is the single most common wall on a first JavaFX project,
and the message does not describe the actual problem.

JavaFX 11+ is a set of **named modules**. When the JVM's main class extends
`Application`, the launcher checks that `javafx.graphics` was loaded as a module from the
**module path**. Maven puts dependencies on the **classpath**. So it refuses:

```
Error: JavaFX runtime components are missing, and are required to run this application
```

`mvn javafx:run` works because the plugin builds a module path for you. A plain
`java -jar` does not.

```java
public final class Launcher {
    public static void main(String[] args) { App.main(args); }
}
```

`Launcher` does not extend `Application`, so the check never runs. JavaFX then
initialises from the classpath quite happily. One indirection, and the whole problem
disappears.

If you skipped it in sprint 13, this is where you would be rewriting the pom, the
manifest and the jpackage invocation to add it.

### 2. Two ways to ship a JVM application

| Tool | Produces | Needs |
|---|---|---|
| `jlink` | A trimmed JVM containing only the modules you use | A fully modular application |
| `jpackage` | A native installer/bundle with a JVM inside | Nothing from the user |

`jpackage` ships with the JDK from 14 onwards. It takes your jars, bundles a Java
runtime, and emits a `.app`/`.dmg` on macOS, `.msi` on Windows, `.deb`/`.rpm` on Linux.
The result is 60–80 MB because it contains a JVM — that is the cost of "no JDK required",
and it is the right trade for a desktop app.

`jlink` would produce something smaller and needs your application and all its
dependencies to be proper modules. SQLite's JDBC driver is an automatic module, which
makes that awkward. `jpackage` with a non-modular application is the pragmatic path.

### 3. A fat jar, and the manifest

`jpackage` wants an input directory of jars and a main class. The simplest reliable route
is one jar containing your code and every dependency:

```xml
<plugin>
  <artifactId>maven-shade-plugin</artifactId>
  <configuration>
    <transformers>
      <transformer implementation="...ManifestResourceTransformer">
        <mainClass>com.expensetracker.Launcher</mainClass>
      </transformer>
    </transformers>
  </configuration>
</plugin>
```

`Launcher`, not `App`. Again.

The shaded jar contains the JavaFX **native libraries for the platform that built it** —
sprint 01's note about the missing `<classifier>`. Your Intel Mac build will not run on
an Apple Silicon Mac. Building for both means a profile per classifier, which is a
genuine piece of work and out of scope here. Know that it is why, rather than being
surprised later.

### 4. Where the database goes

Sprint 06 wrote this:

```java
Path userData = Path.of(home, "Library", "Application Support", "ExpenseTracker");
```

Here is why. Inside a `.app` bundle the working directory is not what you expect and the
bundle itself is **read-only** — signed, and on newer macOS possibly quarantined.
`./data/expenses.db` would fail to create, and the app would not start.

The specification's done-when is exact: *"the database lands in a user-data directory,
not next to the executable."*

The fallback chain still applies, so `-Dexpenses.db=...` overrides it for testing and
development still uses `./data`. One resolution method, three environments.

## What you build

- `maven-shade-plugin` producing a runnable fat jar
- An `.icns` app icon
- A `jpackage` invocation, scripted
- The README section on running it

## Definition of done

- [ ] `java -jar target/expense-tracker-1.0-SNAPSHOT.jar` starts the app
- [ ] `jpackage` produces `ExpenseTracker.app` and a `.dmg`
- [ ] The app launches by double-clicking, with **no JDK installed on the PATH**
- [ ] The database lands in `~/Library/Application Support/ExpenseTracker/`
- [ ] The status bar shows that path
- [ ] The icon appears in the Dock and in Finder
- [ ] Quitting leaves no process behind

---

## Reading check

1. `mvn javafx:run` works but `java -jar` fails with "JavaFX runtime components are
   missing". What is different about how the two start the JVM?
2. `Launcher` does not extend `Application` and that fixes it. Explain the mechanism, not
   just the recipe.
3. The bundle is read-only. Name two things besides the database that would break in an
   application that wrote next to its executable.
4. The `.app` is 70 MB. What is in it, and what would `jlink` change?
