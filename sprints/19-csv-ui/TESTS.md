# Sprint 19 · Tests

Completes **spec test 6** at the UI level, and adds the three TestFX smoke tests.

---

## Automated

| Test | Tag | Proves |
|---|---|---|
| `theWindowOpensAgainstAFreshDatabase` | `ui` | Migrations run, window appears |
| `addingAnExpenseThroughTheDialogMakesARowAppear` | `ui` | The add path works end to end |
| `anInvalidAmountKeepsTheDialogOpenWithSaveDisabled` | `ui` | Sprint 17's binding, through the real UI |
| `expectedRowsCountsRecordsNotLines` | none | The header is excluded from the denominator |
| `expectedRowsReturnsMinusOneForAnUnreadableFile` | none | Indeterminate rather than a crash |

The last two need no toolkit — make `expectedRows` package-private.

## Confirm the exclusion works

```sh
mvn clean test                          # ui and slow excluded — must be green
mvn test -DexcludedGroups=              # everything, needs a display
```

The first command is the specification's definition of done: *"`mvn clean test` is green
from a fresh clone, with no database file present beforehand."* Check it really is by
running it from a clone, not just from your working copy:

```sh
cd /tmp && git clone /Users/i7/Desktop/Expense_Tracker fresh && cd fresh && mvn clean test
```

That catches the whole class of bug where something works because of a file sitting in
your working directory that was never committed.

---

## The manual checklist

### Export

1. **Export the current month.** The suggested filename is `expenses-2026-09.csv`.
2. **Open it in a text editor.** Header row, then one line per visible expense.
3. **The filter is honoured.** Filter to Groceries, export, check only groceries are in
   the file.
4. **Cancel the chooser.** Nothing happens, no error.
5. **Overwrite an existing file.** The platform's own "already exists" prompt appears —
   you did not write it.
6. **A description with a comma** comes out quoted.

### Round trip — the requirement

7. **Export September, then import the same file.**
8. Every expense now appears **twice** — because export omits the id and import creates
   new expenses. That is the documented decision from sprint 12, not a bug. Check it is
   written in your project README.
9. The amounts, dates, categories and descriptions of the duplicates are **identical** to
   the originals. That is "re-imported with no loss".
10. Delete the duplicates before carrying on.

### Import failures — spec test 6

11. Edit an exported file and corrupt the **fifth line** — change the amount to `abc`.
12. Import it. You should see **"line 5: amount must be a plain decimal: 'abc'"** and
    **"Nothing was imported."**
13. **Check the row count is unchanged:**

    ```sh
    sqlite3 data/expenses.db "SELECT COUNT(*) FROM expenses;"
    ```

    Exactly what it was before. That is spec test 6, observed rather than asserted.
14. Try a bad category (`FOOD`). The message **lists the valid categories** — sprint 02's
    error text, written eighteen sprints ago, arriving in front of the user.
15. Try a future date. "date cannot be in the future", with the line number.
16. Try a file with the wrong header. Line 1, and it says what was expected.
17. Try importing a `.txt`. The chooser's filter makes it awkward to select, which is the
    point — but if you force it, the header check rejects it cleanly.

### Cancel — the other requirement

18. Build a large file: export a month of 50 000 rows.
19. Import it, and **click Cancel** while the bar is moving.
20. **The dialog closes** and says "Import cancelled. Nothing was imported."
21. **The row count is unchanged.** Sprint 09's rollback, all the way from a button.
22. **The window is responsive** immediately afterwards.

If the dialog stays up after Cancel, `setOnCancelled` is missing or the event filter is
not consuming. If it vanishes instantly but the count is wrong, the rollback did not
finish before the dialog closed.

### Progress feedback

23. On the big file, the bar shows **indeterminate** while parsing, then a real
    percentage while inserting.
24. The message text changes between the two phases.
25. The main window is **blocked** during the import — that is `APPLICATION_MODAL`
    working.

### Close the dialog with the window control

26. Start a big import and close the progress dialog with its × rather than Cancel.
    Same result as Cancel: rolled back, nothing imported. That is `setOnCloseRequest`.

### And still

27. `jps -l` after closing — with an import mid-flight, even. The worker is a daemon and
    `stop()` shuts it down, so closing the window during an import should not hang.

---

## The whole definition of done

This is the last feature sprint, so walk the specification's list:

- [ ] `mvn clean test` green from a fresh clone, no database present beforehand
- [ ] `mvn javafx:run` starts the app from that same clone
- [ ] The app creates and migrates its own database before the window appears
- [ ] Nothing outside `store` imports `java.sql` — guard test
- [ ] Nothing outside `ui` imports `javafx.*` — guard test, two documented exceptions
- [ ] No SQL built by string concatenation with user input
- [ ] No public method returns `null`
- [ ] No service call on the FX Application Thread
- [ ] Closing the window exits the JVM cleanly
- [ ] **You can explain every line without re-reading it**

The last one is the only one without a check command. Open five files at random and try.
Where you cannot, that is the sprint to revisit — and revisiting it is worth more than
moving on to sprint 20.
