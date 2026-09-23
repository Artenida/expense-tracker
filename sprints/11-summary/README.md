# Sprint 11 · The summary, twice

**Time:** ~3 hours · **Prerequisites:** 10 · **Produces:** `SummaryService` with two independent implementations

---

## Purpose

The specification calls this its "deliberate exercise", and it is the most valuable
sprint in the project. You build the same answer two ways — once by asking the database
to group, once by loading the rows and grouping them in Java — keep both, and test that
they agree.

The point is not that either is better. It is that **knowing where work belongs is a
judgement you will be asked for constantly**, and this is the cheapest possible place to
develop an opinion. Two implementations of one small function, side by side, with a test
that will not let you fool yourself.

You also get the whole Streams API in a context where you can check every answer by
hand.

## New concepts

### 1. A stream is a pipeline, not a collection

```java
expenses.stream()                                   // source
        .filter(e -> e.category() == GROCERIES)     // intermediate — lazy
        .mapToLong(e -> Money.toCents(e.amount()))  // intermediate — lazy
        .sum();                                     // terminal — runs everything
```

Nothing happens until the terminal operation. The intermediate calls build a description
of the work; `sum()` executes it, in one pass over the source.

Two consequences that surprise people:

- **A stream is consumed once.** Call a second terminal operation on the same stream and
  it throws `IllegalStateException`. Streams are not collections; they are more like an
  iterator with a plan attached.
- **Chain order matters for cost, not for correctness.** `filter` before `map` does less
  work than `map` before `filter`, for the same answer.

### 2. `groupingBy` — the one that replaces `GROUP BY`

```java
Map<Category, Long> spentByCategory = expenses.stream()
    .collect(Collectors.groupingBy(
        Expense::category,                              // the key
        Collectors.summingLong(e -> Money.toCents(e.amount()))));  // what to do with each group
```

That is `SELECT category, SUM(amount_cents) ... GROUP BY category`, expressed in Java.
The second argument is a **downstream collector** — it decides what each group collapses
into. Swap `summingLong` for `counting()` and you get `COUNT(*)`; for `toList()` and you
get the rows themselves.

The `Expense::category` is a **method reference**, shorthand for `e -> e.category()`.
Use it when the lambda does nothing but call one method; write the lambda out when it
does anything else.

### 3. The result of `groupingBy` has no order

`groupingBy` returns a `HashMap`. Iterating it gives you the categories in hash order,
which is stable within one JVM run and **not guaranteed across runs or across Java
versions**.

That is the single most likely cause of this sprint's test failing intermittently. The
SQL version comes back ordered by `ORDER BY total DESC, category ASC`; the stream
version comes back in whatever order the hash buckets are in. Sort the stream version
explicitly with the same comparator:

```java
.sorted(Comparator.comparing(CategoryTotal::spent).reversed()
                  .thenComparing(ct -> ct.category().name()))
```

`Comparator.comparing(...).reversed().thenComparing(...)` reads in the order you say it:
compare by spent, reverse that, then break ties by category name. Note `reversed()`
applies to everything before it, so the `thenComparing` is **not** reversed — which is
what you want, and which is worth checking rather than assuming.

### 4. `max` and `Optional`

```java
Optional<Expense> largest = expenses.stream().max(byAmountThenDateThenId);
```

`max` returns `Optional` because an empty stream has no maximum. That is the same
`Optional<Expense> largest` component sprint 05 put on `MonthSummary`, and the empty
month falls out of the type system rather than needing a special case.

**The comparator must match the SQL exactly.** Sprint 09's `findTop` orders by
`amount_cents DESC, spent_on DESC, id DESC`. Two expenses of 50.00 in the same month —
common — and a comparator that only compares amounts will pick whichever the stream
happened to see first. Then `viaSql` and `viaStream` disagree, on data that looks
completely ordinary.

### 5. Summing money in a stream

```java
// wrong — the identity has scale 0, so an empty month gives 0 not 0.00
expenses.stream().map(Expense::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

// right — sum in cents, convert once
Money.fromCents(expenses.stream().mapToLong(e -> Money.toCents(e.amount())).sum());
```

Summing in cents is exact `long` arithmetic and produces a `BigDecimal` of scale 2 at
the end, by construction. Sprint 05's compact constructor would paper over the scale
difference anyway — but relying on that is relying on a fix for a problem you did not
need to create.

## The three sentences for the README

The specification asks you to write, in your own words, when each approach is right.
Do it *after* both implementations pass, while the trade-off is concrete. A starting
point to argue with rather than copy:

> **Push the aggregation into the database** when the answer is much smaller than the
> input and an index can serve the query — 4 000 rows collapsing to 7 means 4 000 rows
> that never cross the process boundary.
> **Keep it in Java** when you need the individual rows anyway, when the logic does not
> express well in SQL, or when you want it testable without a database.
> **Above all, do not split one calculation across both** — a total computed half in SQL
> and half in Java is the version nobody can debug.

Measure before you commit to the first sentence. On the 50 000-row fixture from sprint
09 you can time both.

## What you build

- `SummaryService` with `viaSql`, `viaStream`, and a `summarise` that picks one
- The equivalence test — spec test 8

## Definition of done

- [ ] Both implementations return `equals` summaries for the same fixture
- [ ] Both handle an empty month without throwing (spec test 10)
- [ ] Both include categories that have a budget but no spending
- [ ] `totalBudgeted` is the sum of **all** configured limits in both
- [ ] Tie-breaking matches: same category order, same `largest`
- [ ] The three sentences are in `README.md`

---

## Reading check

1. `.filter(...)` then `.count()` on a million-element stream — how many passes over
   the data, and why?
2. `groupingBy` returns a `HashMap`. Name the concrete failure that causes here, and
   two different ways to fix it.
3. `Comparator.comparing(X).reversed().thenComparing(Y)` — is `Y` reversed? How would
   you write it so `Y` *is* reversed?
4. Both implementations compute `average`. If one divided before rounding and the other
   after, when exactly would the test fail — and would you notice on typical data?
