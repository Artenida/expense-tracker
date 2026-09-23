# Sprint 16 · Tests

Little to automate, a lot to check by hand. That balance is deliberate and worth
understanding rather than apologising for.

---

## What is automated

| Test | File | Proves |
|---|---|---|
| `currentFilterWithoutACategory` | `FilterPanelTest` (`@Tag("ui")`) | `null` selection → `Optional.empty()` |
| `currentFilterWithACategory` | same | A selection → that category |
| `summaryServiceIsCalledWithTheSelectedMonth` | `MainViewTest` | With a fake service, the month reaching `summarise` is the one selected |

`FilterPanel` needs a toolkit (constructing a `ComboBox` does), so those are tagged `ui`.

## The test that actually matters

The specification's filter requirement is *"the filters combine in **one** SQL statement
— never load-everything-then-filter-in-Java"*. That is invisible to a UI test and easy to
assert at the seam:

```java
@Test
void changingTheFilterIssuesExactlyOneQueryWithBothCriteria() {
    RecordingExpenseStore store = new RecordingExpenseStore();
    ExpenseService service = new ExpenseService(store);

    service.find(ExpenseFilter.of(YearMonth.of(2026, 9), Category.GROCERIES));

    assertEquals(1, store.findCalls().size());
    ExpenseFilter used = store.findCalls().get(0);
    assertEquals(YearMonth.of(2026, 9), used.month());
    assertEquals(Optional.of(Category.GROCERIES), used.category());
}
```

`RecordingExpenseStore` is sprint 10's fake with a list of the filters it was handed. No
toolkit, no database, and it proves the thing the specification cares about: one call,
both criteria, no post-filtering above the store.

If a future refactor introduced `find(month)` followed by a `.filter()` in Java, this
test fails and the UI looks identical.

## Why the rest is manual

The panel's job is to *look right*. A TestFX assertion that a `Label` contains
`"spent 236.40 of 950.00 budgeted"` restates the code in a slower language and breaks
every time the wording changes — which it will, in each of the next three sprints.

The specification's own position: *"If a behaviour is worth testing thoroughly, that is
a signal it belongs in a service where you can test it in milliseconds."* Everything
numeric here was tested in sprint 11. What is left is arrangement, and eyes are better
at that than assertions.

---

## The manual checklist

Seed two months of data with different categories first.

### Filtering

1. **Month changes the table.** Pick each of three months; the rows change; the status
   bar does not.
2. **Category changes the table.** Pick `Groceries`; only groceries. Back to `All
   categories`; everything.
3. **Both combine.** September + Groceries shows the intersection, not the union.
4. **An empty month.** The table shows "No expenses for this selection"; the panel shows
   zeroes — **not** a blank panel and not an error dialog.
5. **Rapid changes.** Click through eight months as fast as you can. The table must end
   on the month that is selected. If it lands on a different one, `runLatest` is not
   wired up.

### The `SortedList` check, finally possible

6. Sort by Amount descending. Change the month. Change back.
   **The sort must still be Amount descending.** This is the sprint 14 gotcha, and this
   is the first sprint where you can actually observe it. If the order resets to date,
   the table has a plain `ObservableList` rather than a `SortedList`.

### The summary

7. **Header.** `September 2026` and `spent X of Y budgeted`.
8. **Totals are right.** Add the amounts in the table by hand. They must match.
9. **`totalBudgeted` includes unspent categories.** Set a budget on a category with no
   expenses; the budgeted total goes up and the category appears with an empty bar.
10. **Colours.** Set a budget so spending is at 50% (green), 85% (amber), 120% (red), and
    one category with no budget (grey).
11. **The exceeded bar is full, not over-full.** At 150%, the bar is completely filled and
    the label says the percentage. If the bar overflows its track, `progress()` is not
    clamping.
12. **Footer.** Entry count matches the row count. Average × count ≈ total. Largest
    matches the biggest row.
13. **Empty month footer.** Entries 0, Average 0.00, **no** "Largest" line at all.

### The accumulating-class bug, on purpose

14. Change `setAll` to `add` in `categoryRow`, then change months back and forth six
    times. Watch the bar colours go wrong or stop responding to status. Change it back.

Two minutes, and it is the JavaFX bug you are most likely to write again.

### Still exits

15. `jps -l` after closing. Two background tasks per reload now, so this check has
    started to mean something.
