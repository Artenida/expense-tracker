# Sprint 18 · Tests

Two automated, the rest manual.

---

## Automated

### The error message table

No toolkit needed if you extract the message building:

```java
@Test
void eachExceptionTypeGetsItsOwnMessage() {
    assertTrue(ErrorDialogs.messageFor(new ExpenseNotFoundException("x"))
                           .contains("no longer exists"));
    assertTrue(ErrorDialogs.messageFor(new ValidationException(List.of("amount is required")))
                           .contains("amount is required"));
    assertTrue(ErrorDialogs.messageFor(new CsvFormatException(5, "bad amount"))
                           .contains("line 5"));
}

@Test
void anUnexpectedExceptionStillProducesAMessage() {
    assertNotNull(ErrorDialogs.messageFor(new IllegalStateException("surprise")));
}

@Test
void noMessageContainsAStackTrace() {
    Stream.of(new ExpenseNotFoundException("x"),
              new StoreException("boom", new RuntimeException()),
              new IllegalStateException("surprise"))
          .forEach(e -> assertFalse(ErrorDialogs.messageFor(e).contains("\tat "),
                                    "message must not contain a stack trace"));
}
```

Make `messageFor` and `headerFor` package-private rather than private so they are
testable. The last test is U-3 as an assertion — it is the rule, checkable.

### Spec test 7 at the UI seam

Already covered in sprints 08 and 10. Re-assert it through `BudgetService` if you like;
the manual check below is what verifies the *pane* does the right thing.

---

## The manual checklist

### Delete

1. **Right-click a row.** A menu with Edit… and Delete.
2. **Right-click empty space below the rows.** **No menu.** If one appears, the
   `Bindings.when(row.emptyProperty())` is missing.
3. **Delete asks first**, and the dialog names the amount, description and date of the
   row you picked.
4. **Cancel does nothing.** The row is still there.
5. **Dismiss with Escape** — also nothing.
6. **OK deletes.** The row goes, the summary updates, the entry count drops by one.
7. **Select a row and press `Delete`.** Same confirmation.
8. **Select a row and press `Backspace`.** Same. On a Mac laptop this is the key actually
   labelled "delete" — if only `Delete` works, most users will think the feature is
   missing.
9. **Press `Delete` with nothing selected.** Nothing happens, no exception in the console.

### The concurrent-delete path

The specification's requirement, and it takes two terminals:

10. Open the app and note a row. In a terminal:

    ```sh
    sqlite3 data/expenses.db "DELETE FROM expenses WHERE id = '<that id>';"
    ```

11. In the app, delete that same row. You should see **"Already gone — That expense no
    longer exists…"**, the table refreshes without it, and **no stack trace anywhere**.

That is *"a row deleted by another process reports a readable message rather than
silently succeeding"*, actually exercised.

### Budgets

12. **Seven rows**, one per category, whether or not a budget is set.
13. **Unset budgets show an empty cell**, not `0.00` — those mean different things.
14. **Double-click a limit cell.** It becomes editable.
15. **Type `450.00`, press Enter.** It saves. Switch to Expenses; the summary shows the
    new budgeted total.
16. **Press Escape while editing.** The old value returns; nothing is saved.
17. **Type `abc`, press Enter.** A validation dialog, and the cell reverts to the stored
    value.
18. **Type `-5`, Enter.** Same. **Type `0`, Enter.** Same — the domain rejects it and so
    does the `CHECK` constraint behind it.
19. **Set the same category twice** — `400.00`, then `450.00`. Then:

    ```sh
    sqlite3 data/expenses.db "SELECT * FROM budgets;"
    ```

    **One row**, value `45000`. Spec test 7, observed.

20. **Set a budget on a category with no spending.** It appears in the summary with an
    empty grey bar, and the budgeted total goes up.

### Top expenses

21. **The tab shows 5 rows by default**, largest first.
22. **Change the spinner to 10.** Ten rows.
23. **Change the month.** The top list follows.
24. **A month with 3 expenses and N = 5** shows 3, not an error.
25. **Verify the database is doing the work.** With a month of 50 000 rows, the tab
    should populate as fast as with 10. If it crawls, something is loading everything and
    sorting in Java.

### The amount sorting fix

26. Add expenses of `9.00`, `24.90` and `100.00`.
27. **Sort by Amount ascending.** `9.00`, `24.90`, `100.00`.

    If you get `100.00`, `24.90`, `9.00`, the column is still holding `String`s and
    sorting alphabetically.
28. **Sort descending.** The reverse.

### Errors stay readable

29. `chmod 444 data/expenses.db`, then try to delete something.
    **"Could not reach the database — The database file is read-only. Check the file
    permissions."** Then `chmod 644`.
30. Check the console: the **full stack trace is in the log**. Both halves of U-3.

### And still

31. `jps -l` after closing.
