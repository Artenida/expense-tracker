# Sprint 14 · Tests

`src/test/java/com/expensetracker/ui/model/ExpenseRowTest.java`

Covers **spec test 12**.

---

## The good news

`ExpenseRow` needs **no JavaFX toolkit**. `ReadOnlyStringWrapper` is a plain object; it
only needs a running toolkit if something binds it into a live scene. So spec test 12
runs in `mvn clean test` alongside the domain tests, in microseconds.

That is not an accident — it is the adapter pattern paying off. The class that does the
formatting is separable from the class that displays it, so the formatting is testable
and only the displaying is not.

## Spec test 12

```java
@Test
void expenseRowReflectsTheExpenseItWraps() {
    Expense expense = Expense.restore("id-1", new BigDecimal("24.9"), Category.GROCERIES,
                                      "Weekly shop", LocalDate.of(2026, 9, 15),
                                      Instant.parse("2026-09-15T10:00:00Z"));

    ExpenseRow row = new ExpenseRow(expense);

    assertEquals("2026-09-15", row.dateProperty().get());       // ISO
    assertEquals("Groceries",  row.categoryProperty().get());   // display name
    assertEquals("Weekly shop", row.descriptionProperty().get());
    assertEquals("24.90",      row.amountProperty().get());     // two decimals
}
```

Note the input amount is `24.9`, scale 1, and the output is `"24.90"`. That is
`Money.format` doing its job, and using a scale-1 input is what makes the assertion
meaningful — `24.90` in would pass even if `format` did nothing.

## Formatting

| Test | Input | Expected |
|---|---|---|
| `wholeAmountsShowTwoDecimals` | `24` | `"24.00"` |
| `singleDecimalIsPadded` | `24.9` | `"24.90"` |
| `largeAmountsAreNotAbbreviated` | `1234567.89` | `"1234567.89"` |
| `categoryUsesDisplayNameNotEnumName` | `GROCERIES` | `"Groceries"` |
| `everyCategoryFormats` | all seven | Each gives its `displayName()` |

`everyCategoryFormats` is a loop over `Category.values()` — it catches the case where
someone adds a category and the display name is blank.

## The source is preserved

```java
@Test
void sourceReturnsTheOriginalExpense() {
    Expense expense = someExpense();
    assertSame(expense, new ExpenseRow(expense).source());
}
```

`assertSame`, not `assertEquals`. Sprint 17's dialog calls `edit(...)` on this object to
preserve the id and `createdAt`; a *copy* would work for equality and lose the point.

| Test | Proves |
|---|---|
| `sourceReturnsTheOriginalExpense` | Identity is preserved |
| `nullExpenseIsRejected` | `NullPointerException` from `requireNonNull`, at construction |
| `propertiesAreReadOnly` | `dateProperty()` returns `ReadOnlyStringProperty` — check the declared type |
| `wrapPreservesOrder` | `ExpenseRow.wrap(list)` keeps the store's ordering |
| `wrapOfEmptyIsEmpty` | Not `null`, not a throw |

`wrapPreservesOrder` matters more than it looks. The store orders newest-first; if `wrap`
reordered, the table would open in the wrong order and the cause would be three classes
away from where you would look.

## What is deliberately untested

- That the table **displays** anything. Needs a toolkit, and the layout changes in every
  remaining sprint.
- That sorting works. Sprint 19 covers it with one TestFX smoke test.
- That the `SortedList` binding is the right way round. **Check this by hand** — it is
  the most likely bug in the sprint and the fastest to verify by clicking a header.

## The manual checklist

1. **Rows appear.** Seed three expenses with the SQLite CLI (see SPEC.md) and run. Three
   rows, newest first.
2. **Formatting.** Amounts right-aligned with two decimals; categories as `Groceries`,
   not `GROCERIES`.
3. **Sorting works.** Click each header. Ascending, then descending, then back.
4. **Sorting survives a refresh.** This is the `SortedList` check, and there is no way to
   trigger a refresh yet — so verify it properly in sprint 16, and for now just confirm
   that clicking a header reorders at all. If it does not, the binding is backwards.
5. **Empty state.** Point the temporary filter at a month with nothing in it. The
   placeholder text should read "No expenses for this selection".
6. **It still exits cleanly.** `jps -l` after closing.

## The experiment worth doing

Before sprint 15, make the problem real:

```java
// in a test, or a throwaway main
store.addAll(randomExpenses(50_000, YearMonth.of(2026, 8), 42L));
```

Point the temporary filter at August 2026 and run the app. The window will take a
visible moment to appear, and during that moment it is **completely unresponsive** — it
will not repaint, it will not move, macOS may grey it out.

That is `expenseService.find` running on the FX Application Thread. Nothing is broken;
the thread that draws the screen is busy reading 50 000 rows, so the screen does not get
drawn.

Sit with it for a moment. Sprint 15 is about this, and the rule is much easier to keep
once you have seen what breaking it looks like.
