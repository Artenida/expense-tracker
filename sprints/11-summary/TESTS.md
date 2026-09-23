# Sprint 11 · Tests

`src/test/java/com/expensetracker/service/SummaryServiceTest.java`

Covers **spec tests 8 and 10**, and R-2's boundaries end to end.

---

## The fixture

Hand-computable, with a deliberate tie in it:

```java
private void seedSeptember() {
    // GROCERIES: 24.90 + 40.00 = 64.90  (2 entries)
    // TRANSPORT: 12.00 + 52.90 = 64.90  (2 entries)  ← identical total, on purpose
    // LEISURE:   15.00                  (1 entry)
    //                        total 144.80 over 5 entries, average 28.96
    store.addAll(List.of(
        expense("24.90", GROCERIES, "shop",   "2026-09-15"),
        expense("40.00", GROCERIES, "market", "2026-09-01"),
        expense("12.00", TRANSPORT, "bus",    "2026-09-15"),
        expense("52.90", TRANSPORT, "train",  "2026-09-20"),
        expense("15.00", LEISURE,   "cinema", "2026-09-30")));

    budgets.upsert(Budget.of(GROCERIES, new BigDecimal("100.00")));
    budgets.upsert(Budget.of(TRANSPORT, new BigDecimal("60.00")));
    budgets.upsert(Budget.of(HEALTH,    new BigDecimal("50.00")));   // no spending
}
```

Three things are deliberate:

- **GROCERIES and TRANSPORT total the same.** This is what forces the tiebreak to be
  defined. With distinct totals the sort order is unambiguous and the bug hides.
- **HEALTH has a budget and no spending.** It must still appear, with `spent = 0.00`.
- **LEISURE has spending and no budget.** It must appear with `NO_BUDGET`.

Work the expected numbers out by hand and put them in a comment. A test whose expected
value you derived from running the code proves only that the code is consistent with
itself.

## Spec test 8 — the whole point

```java
@Test
void theSqlAndStreamSummariesAreIdentical() {
    seedSeptember();
    YearMonth month = YearMonth.of(2026, 9);

    assertEquals(service.viaSql(month), service.viaStream(month));
}
```

One line, and it compares every component: totals, count, average, the ordered list and
the largest expense — because `MonthSummary` is a record and its generated `equals` does
all of that.

When it fails, the `toString` of both records is in the failure message, which is why
sprint 05 bothered to note that generated `toString` names every component.

Run it against **several** months:

```java
@ParameterizedTest
@ValueSource(strings = {"2026-09", "2026-08", "2026-07", "2025-12"})
void theTwoImplementationsAgree(String month) {
    seedSeptember();
    YearMonth ym = YearMonth.parse(month);
    assertEquals(service.viaSql(ym), service.viaStream(ym));
}
```

`2026-08` and `2026-07` have no expenses but do have budgets — a case that is easy to
get different between the two, because one starts from spending and the other from a
`GROUP BY` that returns nothing.

## The components, checked individually

The equality test says they agree. These say they are *right*:

| Test | Expected |
|---|---|
| `totalSpentIsTheSumOfEveryExpense` | `144.80` |
| `entryCountIsTheNumberOfExpenses` | `5` |
| `averageIsTotalOverCount` | `28.96` |
| `totalBudgetedSumsEveryConfiguredLimit` | `210.00` — includes HEALTH's 50.00 |
| `largestIsTheBiggestSingleExpense` | `52.90`, the train |
| `categoriesWithABudgetButNoSpendingAppear` | HEALTH present, `spent` is `0.00` |
| `categoriesWithSpendingButNoBudgetAppear` | LEISURE present, status `NO_BUDGET` |
| `categoriesWithNeitherAreAbsent` | HOUSING and EDUCATION are not in the list |

Run each one against **both** implementations. A `@ParameterizedTest` over a
`Function<YearMonth, MonthSummary>` keeps it to one method per assertion:

```java
static Stream<Arguments> implementations() {
    return Stream.of(Arguments.of("sql",    (Function<YearMonth, MonthSummary>) service::viaSql),
                     Arguments.of("stream", (Function<YearMonth, MonthSummary>) service::viaStream));
}
```

## Ordering and ties

```java
@Test
void categoriesWithEqualSpendingAreOrderedByName() {
    seedSeptember();

    List<Category> order = service.viaSql(YearMonth.of(2026, 9)).totals()
                                  .stream().map(CategoryTotal::category).toList();

    // GROCERIES and TRANSPORT both total 64.90 → alphabetical
    assertEquals(GROCERIES, order.get(0));
    assertEquals(TRANSPORT, order.get(1));
    assertEquals(LEISURE,   order.get(2));      // 15.00
    assertEquals(HEALTH,    order.get(3));      // 0.00
}
```

Run the same assertion against `viaStream`. If it passes for SQL and fails for the
stream, you forgot the `.sorted(...)` and are seeing `HashMap` order.

### The largest-expense tiebreak

```java
@Test
void tiedLargestExpensesResolveTheSameWayInBothImplementations() {
    store.addAll(List.of(
        expense("50.00", GROCERIES, "first",  "2026-09-10"),
        expense("50.00", TRANSPORT, "second", "2026-09-20")));

    YearMonth month = YearMonth.of(2026, 9);
    assertEquals(service.viaSql(month).largest(), service.viaStream(month).largest());
}
```

This is the test that catches a comparator with only one key. It will pass by luck
roughly half the time if you get it wrong, so run it a few times — or better, assert the
specific expense, since the tiebreak rule says the later date wins.

## Spec test 10 — the empty month

| Test | Proves |
|---|---|
| `anEmptyMonthReturnsZeroesViaSql` | No throw; `totalSpent`, `average` both `0.00` |
| `anEmptyMonthReturnsZeroesViaStream` | Same |
| `anEmptyMonthHasNoLargest` | `largest().isEmpty()` |
| `anEmptyMonthWithBudgetsStillShowsThem` | Budgets configured, nothing spent: rows present, all `spent = 0.00` |
| `anEmptyMonthWithNoBudgetsHasNoTotals` | `totals().isEmpty()` |

## R-2 boundaries, end to end

Sprint 02 tested `BudgetStatus.of` in isolation. Here the same boundaries travel through
a real database, a real `SUM`, and a real conversion:

```java
@ParameterizedTest
@CsvSource({
    "79.99, OK",
    "80.00, WARNING",
    "99.99, WARNING",
    "100.00, EXCEEDED",
    "150.00, EXCEEDED"
})
void budgetStatusBoundariesSurviveTheRoundTrip(String spent, BudgetStatus expected) {
    budgets.upsert(Budget.of(GROCERIES, new BigDecimal("100.00")));
    store.add(expense(spent, GROCERIES, "test", "2026-09-15"));

    CategoryTotal total = service.summarise(YearMonth.of(2026, 9)).totals().get(0);
    assertEquals(expected, total.status());
}
```

If sprint 02's cross-multiplication were replaced by rounded-percentage comparison, the
`79.99 → OK` case would still pass here. Keep sprint 02's `79_996` test — it is the one
with teeth.

## Timing both, for the README

Not an assertion — a measurement, so your three sentences are informed:

```java
@Test
@Tag("slow")
void compareBothImplementationsOnFiftyThousandRows() {
    store.addAll(randomExpenses(50_000, YearMonth.of(2026, 8), 42L));
    YearMonth month = YearMonth.of(2026, 8);

    service.viaSql(month);  service.viaStream(month);      // warm up

    long sql    = time(() -> service.viaSql(month));
    long stream = time(() -> service.viaStream(month));

    System.out.printf("sql=%d ms  stream=%d ms%n", sql, stream);
}
```

Expect the SQL version to be several times faster, for a reason you can articulate: it
transfers seven rows where the stream version transfers fifty thousand and constructs
fifty thousand `Expense` objects. Put the actual numbers in your README — a measured
claim is worth more than a remembered rule.
