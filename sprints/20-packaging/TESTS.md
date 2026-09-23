# Sprint 20 · Tests

Nothing new to automate. Packaging is verified by running the thing.

---

## The one automated check worth adding

```java
@Test
void launcherDoesNotExtendApplication() {
    assertFalse(javafx.application.Application.class.isAssignableFrom(Launcher.class),
        "Launcher must NOT extend Application, or jpackage builds fail with "
      + "'JavaFX runtime components are missing' — see sprint 20");
}
```

It looks absurd — a test asserting that a class does *not* extend something. It is worth
it because the failure it prevents happens at **package time**, not at build time, weeks
after someone "tidied up" by merging `Launcher` into `App`. The assertion message carries
the explanation to whoever hits it.

This is a general pattern worth recognising: when a constraint exists for a reason
invisible in the code, a test is a place to write the reason down where it will be read.

---

## The manual checklist

### The fat jar

1. `mvn clean package` succeeds.
2. `java -jar target/expense-tracker-1.0-SNAPSHOT.jar` opens the window.
3. No warnings about invalid signature files. If there are, the `META-INF/*.SF` filter is
   missing.

### Reproduce the trap, once

4. Change the shade plugin's `mainClass` to `com.expensetracker.App`, rebuild, and run
   `java -jar ...`:

   ```
   Error: JavaFX runtime components are missing, and are required to run this application
   ```

5. Change it back to `Launcher`. It works.

Five minutes, and it converts the `Launcher` class from a mysterious piece of cargo cult
into something you understand. Do it — it is the highest-value exercise in the sprint.

### The bundle

6. `./scripts/package-mac.sh` succeeds.
7. A `.dmg` in `build/dist/`.
8. Mount it; drag to Applications.
9. **Double-click the app in Finder.** It opens.
10. The **icon** is right in Finder and in the Dock.
11. The window title is "Expense Tracker".

### No JDK required

12. ```sh
    env -i HOME="$HOME" /Applications/ExpenseTracker.app/Contents/MacOS/ExpenseTracker
    ```
    It starts. Nothing external was needed.

13. Optional and convincing: temporarily rename your JDK
    (`sudo mv /Library/Java/JavaVirtualMachines/temurin-21.jdk /tmp/`), confirm
    `java -version` fails, and confirm the app still opens. Put it back afterwards.

### The database location

14. ```sh
    rm -rf ~/Library/Application\ Support/ExpenseTracker/
    open /Applications/ExpenseTracker.app
    ```
15. The window appears, status bar reads
    `/Users/you/Library/Application Support/ExpenseTracker/expenses.db` and `schema v2`.
16. ```sh
    ls ~/Library/Application\ Support/ExpenseTracker/
    ```
    `expenses.db`, `expenses.db-shm`, `expenses.db-wal`.
17. **Nothing was written inside the bundle:**
    ```sh
    find /Applications/ExpenseTracker.app -name '*.db'
    ```
    No output.

### Full function from the bundle

Everything, in the packaged environment — this is the run that catches a resource that
works from `target/classes` and not from inside a jar:

18. Add an expense.
19. Edit it.
20. Delete it.
21. Set a budget; check the summary updates.
22. Export a CSV to the Desktop.
23. Import it back.
24. Sort by every column.
25. Change months and categories.
26. **Quit, reopen — the data is still there.**

If migrations or `app.css` fail here but worked from Maven, the cause is a
`getResourceAsStream` that should have been used and was not. Sprint 06 warned about
exactly this.

### Clean exit

27. Quit with ⌘Q. `jps -l` — nothing. `ps aux | grep ExpenseTracker` — nothing.

---

## Then: the last item on the definition of done

> **You can explain every line without re-reading it.**

The only one with no command. A way to actually test it:

Open five files at random — say `JdbcExpenseStore`, `BackgroundRunner`, `MonthSummary`,
`ExpenseDialog`, `SchemaMigrator` — and for each, without scrolling anywhere else:

- Why does this class exist, and what would break without it?
- Why is each dependency in the constructor rather than created inside?
- Which of the specification's rules does it uphold?
- What is the one line in it you would have got wrong a month ago?

Where you cannot answer, that is the sprint to revisit. Going back is worth more than
moving on — the project is finished either way, and the point was never the application.

---

## What comes next, if you want it

From the specification's own list, in increasing order of what they teach:

| Next | Teaches |
|---|---|
| A `PieChart` of spending by category, fed from `MonthSummary` | JavaFX charts, and that the summary was the right abstraction |
| Replace `SchemaMigrator` with **Flyway** | How little changes when you adopt a tool whose job you already understand |
| The optional Postgres swap | Whether your layering actually holds — a new store class and a connection string, with `service` and `ui` untouched |
| Rewrite `store` with **Spring Data JPA** | Compare the generated SQL with yours, now that you can read it |
| Wrap the services in a **Spring Boot REST API**, point a thin JavaFX client at it | Smaller than it sounds, because `ui` only ever talked to service interfaces |

The Postgres swap is the one that grades the work. Everything else adds features; that
one tests whether the twenty sprints built what they claimed to.
