# Sprint 08 · Tests

Extends `JdbcExpenseStoreTest`; adds `JdbcBudgetStoreTest`.

Covers **spec test 7**.

---

## A fixture worth building once

Most tests here need several expenses across two months and two categories. Put it in a
helper so each test reads as its assertion rather than its setup:

```java
private void seed() {
    store.add(expense("24.90", GROCERIES, "shop",   "2026-09-15"));
    store.add(expense("12.00", TRANSPORT, "bus",    "2026-09-15"));   // same day
    store.add(expense("40.00", GROCERIES, "market", "2026-09-01"));   // first of month
    store.add(expense("15.00", LEISURE,   "cinema", "2026-09-30"));   // last of month
    store.add(expense("99.00", GROCERIES, "august", "2026-08-31"));   // adjacent month
    store.add(expense("11.00", HOUSING,   "october","2026-10-01"));   // adjacent month
}
```

The last two rows are the point. A `BETWEEN` that is subtly wrong still passes every
test where all the data is inside the range.

## Filtering

| Test | Proves |
|---|---|
| `findReturnsOnlyTheRequestedMonth` | 4 rows for September; the August and October rows are absent |
| `theFirstDayOfTheMonthIsIncluded` | `BETWEEN` is inclusive at the lower bound |
| `theLastDayOfTheMonthIsIncluded` | …and at the upper bound |
| `findWithACategoryFiltersToIt` | September + `GROCERIES` gives 2 rows |
| `findWithoutACategoryReturnsAll` | September + empty gives 4 |
| `findReturnsEmptyForAMonthWithNothing` | An empty list, not `null`, not a throw |
| `theReturnedListIsImmutable` | `assertThrows(UnsupportedOperationException.class, () -> result.add(null))` |

### Ordering

```java
@Test
void resultsAreNewestFirst() {
    seed();
    List<Expense> found = store.find(ExpenseFilter.of(YearMonth.of(2026, 9)));

    assertEquals(LocalDate.of(2026, 9, 30), found.get(0).date());
    assertEquals(LocalDate.of(2026, 9,  1), found.get(3).date());
}

@Test
void sameDayOrderingIsStableAcrossRuns() {
    seed();
    ExpenseFilter filter = ExpenseFilter.of(YearMonth.of(2026, 9));

    List<String> first  = store.find(filter).stream().map(Expense::id).toList();
    for (int i = 0; i < 20; i++) {
        assertEquals(first, store.find(filter).stream().map(Expense::id).toList());
    }
}
```

Twenty repetitions because an unstable order is *usually* stable — SQLite tends to
return rows in rowid order until something changes. The test is cheap and it documents
the requirement even on the runs where it could not have failed.

## Update

| Test | Proves |
|---|---|
| `updateChangesTheStoredValues` | Amount, category, description and date all change |
| `updateReturnsTrueWhenARowChanged` | |
| `updateReturnsFalseForAnUnknownId` | No throw — a `boolean` |
| `updateDoesNotChangeTheId` | Obvious, and it catches a parameter-order mistake |
| `updateDoesNotChangeCreatedAt` | The `UPDATE` statement omits the column; this proves it |

The last two are the tests that catch off-by-one parameter binding. Get the numbering
wrong and you might write the description into `spent_on`, which `LocalDate.parse`
rejects on the way back out — but you might also write a valid-looking value somewhere
harmless, and only a per-field assertion notices.

## Delete

| Test | Proves |
|---|---|
| `deleteRemovesTheRow` | `findById` is empty afterwards |
| `deleteReturnsTrueWhenARowWasRemoved` | |
| `deleteReturnsFalseForAnUnknownId` | The requirement behind the spec's "readable message" |
| `deleteIsIdempotentInEffect` | Deleting twice: `true` then `false`, no throw |
| `deleteLeavesOtherRowsAlone` | Seed 4, delete 1, expect 3 — catches a missing `WHERE` |

`deleteLeavesOtherRowsAlone` looks paranoid. `DELETE FROM expenses` with the `WHERE`
accidentally dropped is a real thing that happens, and it is the one bug in this sprint
that destroys data rather than displaying it wrongly.

## Budgets — spec test 7

```java
@Test
void settingABudgetTwiceLeavesOneRowWithTheNewerValue() {
    budgets.upsert(Budget.of(Category.GROCERIES, new BigDecimal("400.00")));
    budgets.upsert(Budget.of(Category.GROCERIES, new BigDecimal("450.00")));

    List<Budget> all = budgets.findAll();
    assertEquals(1, all.size());                                    // one row
    assertEquals(0, new BigDecimal("450.00").compareTo(all.get(0).monthlyLimit()));
}
```

Both assertions are load-bearing. Checking only the value would pass if the upsert
inserted a second row and `findAll` happened to return the newer one first.

| Test | Proves |
|---|---|
| `upsertInsertsWhenAbsent` | The insert half of `ON CONFLICT` |
| `upsertUpdatesUpdatedAt` | The timestamp moves on the second call |
| `budgetsForDifferentCategoriesCoexist` | The conflict is on `category`, not on everything |
| `findByCategoryReturnsEmptyWhenUnset` | `Optional.empty()` — the `NO_BUDGET` path |
| `findAllIsOrderedByCategory` | Deterministic output for the budgets pane |
| `limitsByCategoryMapsEveryBudget` | The `default` method works through the interface |
| `zeroLimitIsRejectedByTheDatabase` | Insert `limit_cents = 0` with a raw `Statement`; the `CHECK` constraint throws |

The last one is worth writing even though sprint 04 already rejects it in the domain.
It proves the database's `CHECK` is a genuine second line of defence rather than
decoration — which is exactly what the specification says it is for.
