# Sprint 05 · Build specification

All three in `src/main/java/com/expensetracker/domain/`.

---

## `CategoryTotal.java`

```java
public record CategoryTotal(
        Category category,
        BigDecimal spent,
        Optional<BigDecimal> limit,
        BigDecimal percentUsed,
        BudgetStatus status) {

    public CategoryTotal {
        Objects.requireNonNull(category);
        Objects.requireNonNull(status);
        spent       = spent.setScale(2, RoundingMode.HALF_UP);
        percentUsed = percentUsed.setScale(2, RoundingMode.HALF_UP);
        limit       = limit.map(l -> l.setScale(2, RoundingMode.HALF_UP));
    }
}
```

### The factory that does the work

```java
public static CategoryTotal of(Category category, long spentCents, OptionalLong limitCents)
```

| Step | Rule |
|---|---|
| `spent` | `Money.fromCents(spentCents)` |
| `limit` | `limitCents.isPresent() ? Optional.of(Money.fromCents(...)) : Optional.empty()` |
| `status` | `BudgetStatus.of(spentCents, limitCents)` — sprint 02 |
| `percentUsed` | `0.00` when there is no limit; otherwise `spentCents * 100 / limitCents` as a `BigDecimal` with scale 2 |

Note the **cents in, `BigDecimal` out** shape. Every caller has cents (that is what the
database holds), the status decision needs cents (sprint 02's cross-multiplication),
and only the displayed values need `BigDecimal`. Converting once, here, keeps the rest
of the project from having to think about it.

For `percentUsed`, divide as `BigDecimal` *after* the status has already been decided:

```java
BigDecimal.valueOf(spentCents)
          .multiply(BigDecimal.valueOf(100))
          .divide(BigDecimal.valueOf(limitCents), 2, RoundingMode.HALF_UP);
```

Rounding here is safe precisely because nothing branches on the result — sprint 02's
`status` already did the deciding.

### The progress-bar value

```java
public double progress() {
    return Math.min(1.0, percentUsed.doubleValue() / 100.0);
}
```

The `Math.min` is the specification's "a bar for an exceeded budget is full, never
over-full". `double` is fine here — it feeds a `ProgressBar` whose whole range is a few
hundred pixels, so a rounding error in the fifteenth decimal place is not observable.
This is the one place in the project where a `double` touching money is correct, and it
is worth understanding why: it is not money any more, it is a fraction of a bar.

---

## `MonthSummary.java`

```java
public record MonthSummary(
        YearMonth yearMonth,
        BigDecimal totalSpent,
        BigDecimal totalBudgeted,
        int entryCount,
        BigDecimal average,
        List<CategoryTotal> totals,
        Optional<Expense> largest) {

    public MonthSummary {
        Objects.requireNonNull(yearMonth);
        if (entryCount < 0) throw new IllegalArgumentException("entryCount < 0");
        totalSpent    = totalSpent.setScale(2, RoundingMode.HALF_UP);
        totalBudgeted = totalBudgeted.setScale(2, RoundingMode.HALF_UP);
        average       = average.setScale(2, RoundingMode.HALF_UP);
        totals        = List.copyOf(totals);
    }

    public static MonthSummary empty(YearMonth month) {
        return new MonthSummary(month, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                                BigDecimal.ZERO, List.of(), Optional.empty());
    }
}
```

`empty` is what spec test 10 asks for — "a month with no expenses returns a summary of
zeroes, not an exception". Note the compact constructor rescales `BigDecimal.ZERO`
(scale 0) to `0.00` (scale 2), which is exactly the normalisation README §4 is about.

### Two definitions you must pin down

Both are ambiguous in the original specification, and the two implementations in sprint
11 will disagree unless you fix them here, in a comment on the record:

- **`totalBudgeted` = the sum of every configured budget limit**, including categories
  with no spending this month. Not "the limits of categories that were spent in".
- **`average` = `totalSpent / entryCount`**, or `0.00` when `entryCount == 0`. Guard the
  division; `BigDecimal.divide` by zero throws `ArithmeticException`.

### Ordering is part of the value

A record's `equals` compares `List`s element by element, **in order**. So spec test 8
passes only if both implementations produce `totals` in the same order. Fix it:

> `totals` is ordered by `spent` descending, then by `category.name()` ascending.

The second key is the one that matters. Without it, two categories with equal spend can
come back in either order — the SQL version in whatever order SQLite's `GROUP BY`
happened to emit, the stream version in `HashMap` iteration order — and the test fails
intermittently. Intermittently is the worst way for a test to fail.

Every category that was spent in appears. Categories with a budget but no spending
appear too, with `spent = 0.00`, so the panel can show an untouched budget. Categories
with neither are omitted.

---

## `ExpenseFilter.java`

```java
public record ExpenseFilter(YearMonth month, Optional<Category> category) {

    public ExpenseFilter {
        Objects.requireNonNull(month);
        Objects.requireNonNull(category);
    }

    public static ExpenseFilter of(YearMonth month)                    { ... }  // all categories
    public static ExpenseFilter of(YearMonth month, Category category) { ... }

    public LocalDate from() { return month.atDay(1); }
    public LocalDate to()   { return month.atEndOfMonth(); }
}
```

### Why `YearMonth` and not two dates

`YearMonth` is what the user picks and what the summary is *about*. Deriving the bounds
here means `atEndOfMonth()` handles February and leap years once, in the JDK, rather
than in whichever layer felt responsible. It also makes an invalid filter — a range
that is not a whole month — impossible to express.

`from()` and `to()` are what sprint 08's `BETWEEN ? AND ?` binds. Both bounds are
**inclusive**, which matches SQL's `BETWEEN`, and the ISO text format compares correctly
as a string — `2026-09-01` ≤ `2026-09-15` ≤ `2026-09-30` lexically as well as
chronologically. That is the whole reason the schema stores dates as ISO text.

### `Optional<Category>` as the "All" case

The combo box in sprint 16 has an "All" entry. `Optional.empty()` is that entry.
Sprint 08 turns it into the `(? IS NULL OR category = ?)` binding — the one place in
the project where a parameter is deliberately bound to `null`.
