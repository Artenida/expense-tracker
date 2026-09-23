# Sprint 09 · Tests

Extends `JdbcExpenseStoreTest`.

Sets up **spec test 6** (sprint 12 completes it) and the fixture for sprint 15.

---

## addAll — the happy path

| Test | Proves |
|---|---|
| `addAllInsertsEveryRow` | 100 in, 100 findable, return value is 100 |
| `addAllReturnsZeroForAnEmptyList` | No connection opened, no throw |
| `addAllReportsProgressForEveryRow` | Collect the `IntConsumer` calls; assert `[1, 2, ..., 100]` |
| `addAllCrossesTheBatchBoundary` | 1 200 rows — proves the in-loop `executeBatch` and the final one cooperate |

`addAllCrossesTheBatchBoundary` is the one that catches a missing final `executeBatch`:
with 1 000 rows exactly, the in-loop flush happens to catch everything and the bug
hides. Use a size that is *not* a multiple of 500.

## addAll — rollback

The reason the method exists:

```java
@Test
void aFailurePartwayThroughLeavesTheTableUnchanged() {
    store.add(expense("5.00", OTHER, "existing", "2026-09-01"));

    Expense duplicate = expense("9.00", OTHER, "dup", "2026-09-02");
    List<Expense> batch = new ArrayList<>(randomExpenses(50, YearMonth.of(2026, 9), 1L));
    batch.add(duplicate);
    batch.add(duplicate);              // same id twice → PRIMARY KEY violation

    assertThrows(StoreException.class, () -> store.addAll(batch));

    assertEquals(1, countRows());      // only the pre-existing row
}
```

`countRows()` is the assertion that matters. "It threw" would also be true of an
implementation that inserted 51 rows and then failed.

| Test | Proves |
|---|---|
| `aFailurePartwayThroughLeavesTheTableUnchanged` | Rollback works |
| `theExceptionCarriesTheSqlCause` | `getCause()` is a `SQLException` |
| `theConnectionIsNotLeaked` | Run the failing import 200 times in a loop; the 201st still succeeds |

`theConnectionIsNotLeaked` is unusual and worth having. A leaked connection on the error
path is invisible in a single test and fatal in production — the specification's
"invisible until the application hangs". Two hundred iterations against a WAL-mode
SQLite file will exhaust the file handles if the `finally` is wrong.

## addAll — cancellation

```java
@Test
void cancellingPartwayThroughLeavesTheTableUnchanged() {
    List<Expense> batch = randomExpenses(1_000, YearMonth.of(2026, 9), 2L);
    AtomicInteger seen = new AtomicInteger();

    int inserted = store.addAll(batch,
        i -> seen.set(i),
        () -> seen.get() >= 600);       // cancel once 600 rows have been bound

    assertEquals(0, inserted);
    assertEquals(0, countRows());
}
```

The `BooleanSupplier` is driven by the progress counter, so the cancellation lands at a
deterministic point rather than depending on timing. A test that cancels "after 50
milliseconds" is a test that fails on a slow CI machine.

| Test | Proves |
|---|---|
| `cancellingPartwayThroughLeavesTheTableUnchanged` | Rollback on cancel |
| `cancellingBeforeTheFirstRowInsertsNothing` | `() -> true` — the guard is checked before the first bind |
| `aCompletedImportIgnoresALateCancel` | Cancel only after the last row; everything commits |

## Aggregates

Seed a known month so the arithmetic is checkable by hand:

| Test | Proves |
|---|---|
| `totalsByCategorySumsCorrectly` | `GROCERIES` = 24.90 + 40.00 = 6 490 cents, 2 entries |
| `totalsByCategoryOmitsCategoriesWithNoSpending` | 3 categories in the fixture, 3 rows |
| `totalsByCategoryIsOrderedByTotalDescending` | Largest first |
| `totalsByCategoryTiesBreakByCategoryName` | Two categories with **identical** totals come back alphabetically — sprint 11 depends on this |
| `totalsByCategoryIsEmptyForAnEmptyMonth` | Empty list, not one row of `NULL` |
| `totalsByCategoryExcludesAdjacentMonths` | The August and October rows do not leak in |

`totalsByCategoryTiesBreakByCategoryName` is the one that prevents a mystery in sprint
11. Construct it deliberately — two categories, same total, and assert the order.

## Top N

| Test | Proves |
|---|---|
| `findTopReturnsTheLargestFirst` | Descending by amount |
| `findTopRespectsTheLimit` | 10 rows seeded, `findTop(..., 5)` returns 5 |
| `findTopReturnsEverythingWhenTheLimitExceedsTheRowCount` | 3 rows, limit 5, returns 3 |
| `findTopIsScopedToTheDateRange` | Adjacent months excluded |
| `findTopRejectsANonPositiveLimit` | `IllegalArgumentException` for 0 and -1 |
| `findTopBreaksTiesStably` | Two expenses of the same amount come back in a fixed order |

## The performance fixture

Tag it so it does not slow the normal test run:

```java
@Test
@Tag("slow")
void fiftyThousandRowsImportInReasonableTime() {
    List<Expense> batch = randomExpenses(50_000, YearMonth.of(2026, 8), 42L);

    long start = System.nanoTime();
    assertEquals(50_000, store.addAll(batch));
    long millis = (System.nanoTime() - start) / 1_000_000;

    assertEquals(50_000, countRows());
    assertTrue(millis < 30_000, "import took " + millis + " ms");
}
```

Add `slow` to the surefire `excludedGroups` alongside `ui`, and run it deliberately with
`mvn test -DexcludedGroups=`.

The 30-second bound is loose on purpose. A **timing** assertion tight enough to be
meaningful is also tight enough to fail on a loaded machine, and a test that fails for
reasons unrelated to your code trains you to ignore failures. This one exists to catch
a catastrophic regression — forgetting the transaction, so every row commits
individually, turns 3 seconds into several minutes. Anything subtler than that, measure
by hand rather than assert.

Keep the database this produces. Sprint 15 needs it.
