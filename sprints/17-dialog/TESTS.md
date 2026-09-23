# Sprint 17 · Tests

Completes **spec test 14** at the UI level. Most verification is manual.

---

## What is worth automating

The validation logic is already tested — sprint 03 covered every input, sprint 04
covered the domain rejection. What is new here is **wiring**, and the honest assessment
is that most of it is only checkable by clicking.

Two things do earn automated tests:

### Spec test 14, at the dialog's boundary

```java
@ParameterizedTest
@ValueSource(strings = {"abc", "", "1.234", "-2", "12,50", "0"})
void invalidAmountsProduceAMessageAndNoDomainObject(String raw) {
    assertFalse(Validation.amount(raw).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> Money.parse(raw));
}
```

This restates sprint 03's test, and that is deliberate: it is the assertion the *dialog*
depends on. If someone relaxed `Validation.amount`, sprint 03's test would change with
it and this one — sitting in the UI test package with a name about the dialog — is the
reminder that a control's behaviour changed too.

### The edit path preserves identity

No toolkit needed, because it tests `edit`, not the dialog:

```java
@Test
void editingKeepsTheIdAndCreatedAt() {
    Expense original = Expense.create(new BigDecimal("10.00"), Category.OTHER,
                                      "lunch", LocalDate.of(2026, 9, 1));

    Expense edited = original.edit(new BigDecimal("12.00"), Category.GROCERIES,
                                   "brunch", LocalDate.of(2026, 9, 2));

    assertEquals(original.id(),        edited.id());
    assertEquals(original.createdAt(), edited.createdAt());
    assertEquals(0, new BigDecimal("12.00").compareTo(edited.amount()));
}
```

The bug this catches — using `create` instead of `edit` in the result converter — would
otherwise show up as a mysterious `ExpenseNotFoundException` when saving.

---

## The manual checklist

### Add

1. **Opens.** Click `Add expense`. The dialog is centred on the main window and the main
   window is not clickable behind it.
2. **Save starts disabled.** The amount is empty, so the form is invalid.
3. **Type `2`.** Save enables. The default date is today and the default category is set,
   so one valid field is enough.
4. **Clear the amount.** Save disables again, and a message appears under the field.
5. **Type `abc`.** "amount must be a number with at most two decimals, for example 24.90".
6. **Type `1.234`.** Same message. **Type `12,50`.** Same message — the locale decision,
   visible.
7. **Type `0`.** "amount must be greater than zero" — a *different* message, because it
   is a different rule.
8. **Clear the description.** Its own message, under the description field, while the
   amount field stays clean.
9. **101 characters in the description.** The message includes the number.
10. **Save a valid expense.** The dialog closes, the row appears, the summary updates.
11. **Cancel.** Nothing is added.
12. **Restart the app.** The expense is still there. This is the specification's "done
    when" for Add, and the only way to check it is to actually restart.

### The date picker

13. Open the date picker. **Future days are greyed and unclickable.** Today is
    selectable.
14. Navigate to next month. Every day is disabled.
15. Navigate back several months. Everything is selectable, and cells do not show stale
    state as you scroll — that is the `super.updateItem` check.

### The layout does not jump

16. Type and clear the amount repeatedly. **The dialog must not resize or twitch** as the
    message appears and disappears. If it does, you set `visible` without `managed`.

### Edit

17. **Double-click a row.** The dialog opens titled "Edit expense" with all four fields
    filled.
18. **Save without changing anything.** The row is unchanged — the specification's
    "untouched fields keep their values".
19. **Change only the amount.** Only the amount changes; category, description and date
    are as they were.
20. **The id is preserved.** Check in the database, not on screen:

    ```sh
    sqlite3 data/expenses.db "SELECT id, amount_cents, created_at FROM expenses;"
    ```

    Edit that expense, run it again. **Same id, same `created_at`, new amount.** If the
    id changed, the result converter is calling `create`.

21. **Double-click empty space below the last row.** Nothing happens — no dialog, no
    exception in the console.
22. **Edit validation.** Clear the description in the edit dialog. Save disables, the
    message appears. Same rules as add, because it is the same code.

### The rule that must not break

23. **No stack trace ever reaches the screen.** Try every invalid input you can think of.
    The dialog reports and stays open. If a `ValidationException` ever produces a dialog,
    something bypassed the Save binding — and per the specification, that is a bug in the
    dialog, not a normal path.

### And still

24. `jps -l` after closing.
