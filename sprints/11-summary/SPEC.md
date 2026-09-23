# Sprint 11 · Build specification

`src/main/java/com/expensetracker/service/SummaryService.java`

---

## The shape

```java
public final class SummaryService {

    private final ExpenseStore expenses;
    private final BudgetStore  budgets;

    public SummaryService(ExpenseStore expenses, BudgetStore budgets) { ... }

    /** The implementation the application uses. */
    public MonthSummary summarise(YearMonth month) { return viaSql(month); }

    /** Aggregation done by the database. */
    public MonthSummary viaSql(YearMonth month) { ... }

    /** Aggregation done in Java over the month's rows. */
    public MonthSummary viaStream(YearMonth month) { ... }
}
```

`summarise` is what sprint 16 calls. Keeping it separate means the UI does not choose an
implementation, and swapping which one is used is a one-line experiment.

Both public implementations, both tested. That is the exercise.

---

## Shared: assembling the category totals

Both implementations end up with the same two things — spending per category, and the
configured limits — and both turn them into a sorted `List<CategoryTotal>`. Write that
once:

```java
private static final Comparator<CategoryTotal> BY_SPEND_THEN_NAME =
    Comparator.comparing(CategoryTotal::spent).reversed()
              .thenComparing(ct -> ct.category().name());

private List<CategoryTotal> buildTotals(Map<Category, Long> spentByCategory,
                                        Map<Category, Long> limitsByCategory) {

    Set<Category> relevant = new LinkedHashSet<>();
    relevant.addAll(spentByCategory.keySet());
    relevant.addAll(limitsByCategory.keySet());

    return relevant.stream()
        .map(category -> CategoryTotal.of(
            category,
            spentByCategory.getOrDefault(category, 0L),
            limitsByCategory.containsKey(category)
                ? OptionalLong.of(limitsByCategory.get(category))
                : OptionalLong.empty()))
        .sorted(BY_SPEND_THEN_NAME)
        .toList();
}
```

The **union** of the two key sets is the rule from sprint 05: a category with a budget
and no spending still shows a row (an empty bar), and a category spent in with no budget
shows `NO_BUDGET`. A category with neither does not appear.

Extracting this is what makes the two implementations genuinely comparable — the only
difference between them is *how they compute `spentByCategory`*, which is the thing
under study. Duplicating the assembly would let a bug in one copy masquerade as a
difference between approaches.

---

## `viaSql`

```java
public MonthSummary viaSql(YearMonth month) {
    ExpenseFilter filter = ExpenseFilter.of(month);

    List<CategorySpend> spends = expenses.totalsByCategory(filter.from(), filter.to());
    Map<Category, Long> limits = budgets.limitsByCategory();

    Map<Category, Long> spentByCategory = spends.stream()
        .collect(Collectors.toMap(CategorySpend::category, CategorySpend::spentCents));

    long totalCents = spends.stream().mapToLong(CategorySpend::spentCents).sum();
    int  entryCount = spends.stream().mapToInt(CategorySpend::entryCount).sum();

    List<Expense> top = expenses.findTop(filter.from(), filter.to(), 1);

    return assemble(month, totalCents, entryCount, limits,
                    buildTotals(spentByCategory, limits),
                    top.isEmpty() ? Optional.empty() : Optional.of(top.get(0)));
}
```

**Three queries**, whatever the month contains: the grouped totals, the budgets, and the
single largest expense. Not one query per category, and not one row per expense.

`findTop(..., 1)` for the largest is the specification's "ordering and `LIMIT` are done
by the database". The alternative — loading the month and taking the max — is what
`viaStream` does, which is the point of having both.

## `viaStream`

```java
public MonthSummary viaStream(YearMonth month) {
    ExpenseFilter filter = ExpenseFilter.of(month);

    List<Expense> rows = expenses.find(filter);          // every row for the month
    Map<Category, Long> limits = budgets.limitsByCategory();

    Map<Category, Long> spentByCategory = rows.stream()
        .collect(Collectors.groupingBy(
            Expense::category,
            Collectors.summingLong(e -> Money.toCents(e.amount()))));

    long totalCents = rows.stream().mapToLong(e -> Money.toCents(e.amount())).sum();

    return assemble(month, totalCents, rows.size(), limits,
                    buildTotals(spentByCategory, limits),
                    rows.stream().max(BY_AMOUNT_THEN_DATE_THEN_ID));
}
```

`rows.size()` rather than a `counting()` collector — the list is already in memory and
its size is the entry count.

### The comparator that must match the SQL

```java
private static final Comparator<Expense> BY_AMOUNT_THEN_DATE_THEN_ID =
    Comparator.comparing(Expense::amount)
              .thenComparing(Expense::date)
              .thenComparing(Expense::id);
```

Used with `max`, this picks the largest amount, then the latest date, then the highest
id — which is exactly sprint 09's `ORDER BY amount_cents DESC, spent_on DESC, id DESC`
taking the first row. `max` and `DESC`-then-`LIMIT 1` agree **only** when the comparator
matches all three keys.

Get this wrong and the test fails on a fixture with two equal amounts, which is the
first realistic fixture you will write.

> Note `Comparator.comparing(Expense::amount)` compares `BigDecimal`s with `compareTo`,
> which is scale-insensitive. That is correct here, and it is the one place in the
> project where `BigDecimal`'s natural ordering is used directly.

---

## Shared: `assemble`

```java
private MonthSummary assemble(YearMonth month, long totalCents, int entryCount,
                              Map<Category, Long> limits,
                              List<CategoryTotal> totals, Optional<Expense> largest) {

    long budgetedCents = limits.values().stream().mapToLong(Long::longValue).sum();

    BigDecimal average = entryCount == 0
        ? BigDecimal.ZERO
        : Money.fromCents(totalCents).divide(BigDecimal.valueOf(entryCount),
                                             2, RoundingMode.HALF_UP);

    return new MonthSummary(month, Money.fromCents(totalCents),
                            Money.fromCents(budgetedCents), entryCount,
                            average, totals, largest);
}
```

Two things pinned down here that the original specification left ambiguous, and that
the two implementations would otherwise resolve differently:

- **`totalBudgeted` = every configured limit**, from `limits.values()`, not only the
  categories present in `totals`. Both implementations get it from the same map, so they
  cannot disagree.
- **`average` is guarded.** `BigDecimal.divide` by zero throws `ArithmeticException`;
  spec test 10 requires zeroes instead. `Money.fromCents(0)` for the total is fine —
  it is only the division that is undefined.

Rounding the average with `HALF_UP` at scale 2 is safe: nothing branches on it, it is
displayed and nothing more. Sprint 02's rule is about rounding before a **decision**.

---

## An empty month

Neither implementation needs a special case:

- `viaSql` — `totalsByCategory` returns an empty list, so the sums are 0 and `findTop`
  returns an empty list.
- `viaStream` — `find` returns an empty list, `groupingBy` gives an empty map, `max`
  gives `Optional.empty()`.

`buildTotals` still emits a row per configured budget, which is right: a month with no
spending should show your budgets sitting untouched, not a blank panel.

Check that `MonthSummary.empty(month)` and `summarise(emptyMonth)` agree when no budgets
are set. If they do not, one of them is wrong about what "empty" means.
