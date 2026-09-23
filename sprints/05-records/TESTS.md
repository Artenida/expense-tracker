# Sprint 05 · Tests

`CategoryTotalTest.java`, `MonthSummaryTest.java`, `ExpenseFilterTest.java`.

Covers **spec test 10**, and sets up **spec test 8** for sprint 11.

---

## CategoryTotal

| Test | Proves |
|---|---|
| `ofComputesSpentAndLimit` | `of(GROCERIES, 23_640, OptionalLong.of(95_000))` gives `236.40` and `950.00` |
| `ofComputesPercentUsed` | Same inputs give `24.88` — check this by hand: 23640×100÷95000 = 24.884… → 24.88 |
| `noLimitGivesEmptyOptionalAndZeroPercent` | `limit().isEmpty()`, `percentUsed()` is `0.00`, `status()` is `NO_BUDGET` |
| `progressIsClampedAtOne` | 150% spent gives `progress() == 1.0`, not `1.5` |
| `progressIsZeroWithNoBudget` | No limit means an empty bar, not a crash |
| `allBigDecimalsHaveScaleTwo` | `spent().scale() == 2` and the same for `percentUsed()` |

## MonthSummary — the empty case (spec test 10)

```java
@Test
void emptyMonthIsZeroesNotAnException() {
    MonthSummary summary = MonthSummary.empty(YearMonth.of(2026, 9));

    assertEquals(0, summary.entryCount());
    assertEquals(0, summary.totalSpent().compareTo(BigDecimal.ZERO));
    assertEquals(0, summary.average().compareTo(BigDecimal.ZERO));
    assertTrue(summary.totals().isEmpty());
    assertTrue(summary.largest().isEmpty());
}
```

`compareTo(...) == 0` rather than `assertEquals` on the `BigDecimal`s — sprint 03's
lesson, applied. `assertEquals(BigDecimal.ZERO, summary.totalSpent())` would **fail**,
because the compact constructor rescaled it to `0.00` and `BigDecimal.equals` is
scale-sensitive. Seeing that failure once is worth more than reading about it.

## MonthSummary — the normalisation that makes sprint 11 work

The most important test in the sprint:

```java
@Test
void summariesAreEqualRegardlessOfHowTheBigDecimalsWereBuilt() {
    YearMonth month = YearMonth.of(2026, 9);

    // as the stream implementation would build it — ZERO has scale 0
    MonthSummary a = new MonthSummary(month, BigDecimal.ZERO, BigDecimal.ZERO,
                                      0, BigDecimal.ZERO, List.of(), Optional.empty());

    // as the SQL implementation would build it — from cents, scale 2
    MonthSummary b = new MonthSummary(month, Money.fromCents(0), Money.fromCents(0),
                                      0, Money.fromCents(0), List.of(), Optional.empty());

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
}
```

Delete the `setScale` lines from the compact constructor and watch this fail. That
failure *is* spec test 8 failing, thirteen sprints early and with a two-line cause
instead of a two-hundred-line one.

## MonthSummary — defensive copying

```java
@Test
void mutatingTheSourceListDoesNotAffectTheSummary() {
    List<CategoryTotal> source = new ArrayList<>();
    source.add(CategoryTotal.of(Category.OTHER, 1_000, OptionalLong.empty()));

    MonthSummary summary = new MonthSummary(YearMonth.of(2026, 9),
        BigDecimal.TEN, BigDecimal.ZERO, 1, BigDecimal.TEN, source, Optional.empty());

    source.clear();
    assertEquals(1, summary.totals().size());
}

@Test
void theReturnedListCannotBeModified() {
    MonthSummary summary = MonthSummary.empty(YearMonth.of(2026, 9));
    assertThrows(UnsupportedOperationException.class,
        () -> summary.totals().add(null));
}
```

The second test is the other half. `List.copyOf` protects in both directions — a caller
cannot change the summary by mutating what they passed in, and cannot change it by
mutating what they got out.

| Test | Proves |
|---|---|
| `negativeEntryCountIsRejected` | The compact constructor validates as well as normalises |
| `toStringIsReadable` | Generated `toString` names every component — you will read this in failure output |

## ExpenseFilter

| Test | Proves |
|---|---|
| `fromIsTheFirstOfTheMonth` | `of(YearMonth.of(2026, 9)).from()` is `2026-09-01` |
| `toIsTheLastOfTheMonth` | …`to()` is `2026-09-30` |
| `februaryInALeapYear` | `YearMonth.of(2024, 2).atEndOfMonth()` is `2024-02-29` |
| `februaryInANonLeapYear` | `YearMonth.of(2026, 2)` gives `2026-02-28` |
| `singleArgFactoryMeansAllCategories` | `of(month).category().isEmpty()` |
| `twoArgFactoryCarriesTheCategory` | `of(month, GROCERIES).category()` contains `GROCERIES` |

The two February tests look like you are testing the JDK rather than your code, and in
a sense you are. They are worth the thirty seconds because they document *why*
`ExpenseFilter` owns the bounds calculation — the next person to read the class sees
immediately what would go wrong if the bounds were computed by each caller.
