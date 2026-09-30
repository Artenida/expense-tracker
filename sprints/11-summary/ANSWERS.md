# Sprint 11 · Reading check answers

Answers to the four questions at the end of `README.md`.

---

## 1. `.filter(...)` then `.count()` on a million-element stream: how many passes over the data, and why?

### One pass

```java
long n = expenses.stream()
        .filter(e -> e.category() == GROCERIES)
        .count();
```

`filter` does no work when it is called. It adds a stage to the pipeline and returns straight
away. Only `count()`, the terminal operation, starts the traversal. It then takes each element
from the source, passes it through the filter, and adds one to the count if the element gets
through, before moving on to the next element.

This is why the README calls a stream "a pipeline, not a collection". No filtered list of
matching elements is ever built, and nothing loops over one. A million elements go through the
filter once, and the only memory used is a single `long` counter.

### A detail worth knowing

When there is **no** filter, since Java 9 `count()` may skip the traversal entirely:
`list.stream().count()` already knows the size from the source, so it returns it without
touching any element. A `filter` removes that shortcut, because the size is no longer known in
advance. So "one pass" is exact here. With no intermediate stages the answer can be zero passes,
which is also the reason not to put side effects in a `peek` and assume they will run.

## 2. `groupingBy` returns a `HashMap`. Name the concrete failure that causes here, and two different ways to fix it.

### The failure

`theSqlAndStreamSummariesAreIdentical` fails **intermittently**. The SQL version returns its
categories in `ORDER BY total DESC, category ASC` order. If the stream version builds its list by
iterating the `HashMap`, it gets the categories in hash-bucket order instead. `MonthSummary` is a
record, and its `equals` compares the `totals` lists element by element, so two lists holding the
same seven totals in a different order are not equal.

The failure is intermittent rather than constant because of how enums hash. `Enum.hashCode()` is
`Object`'s identity hash, which is not derived from the name or the ordinal. It can differ from
one JVM start to the next. So the same test, on the same data and the same machine, can pass in
the IDE and fail in `mvn test`. That is the worst kind of failure to debug.

### Fix 1: sort explicitly (what `SummaryService` does)

```java
.sorted(Comparator.comparing(CategoryTotal::spent).reversed()
                  .thenComparing(ct -> ct.category().name()))
```

This does not depend on the map's order at all. The order comes from the data and a stated rule,
and that rule is exactly the SQL's `ORDER BY`. Both implementations call the same `buildTotals`,
so they sort with the same comparator.

### Fix 2: ask `groupingBy` for an ordered map

The three-argument `groupingBy` takes a map factory:

```java
Collectors.groupingBy(Expense::category,
                      () -> new EnumMap<>(Category.class),
                      Collectors.summingLong(e -> Money.toCents(e.amount())))
```

An `EnumMap` iterates in declaration order, and a `TreeMap` in natural order. Either way the
order is deterministic, which cures the flakiness. It is the **wrong** order for this summary,
though, because the panel wants biggest spend first. On its own this fix turns an intermittent
failure into a constant one. It is the right fix when key order is what you want, for example a
report laid out by category. Here it would still need the sort from fix 1.

A third option, `toMap(..., LinkedHashMap::new)`, keeps insertion order. That is only as
deterministic as whatever order the elements arrived in.

## 3. `Comparator.comparing(X).reversed().thenComparing(Y)`: is `Y` reversed? How would you write it so `Y` *is* reversed?

### No

Each method returns a new comparator that wraps the one it was called on. `reversed()` wraps
`comparing(X)`, so it reverses X only. `thenComparing(Y)` is then called on that reversed
comparator and adds Y as a tiebreak. Y is added after the reversal, so it keeps its natural,
ascending order.

That is what `BY_SPEND_THEN_NAME` depends on: spend descending, then name **ascending**. The
test `categoriesWithEqualSpendingAreOrderedByName` checks it with GROCERIES and TRANSPORT tied at
64.90, rather than assuming it.

### Reversing `Y` as well

Either reverse Y on its own:

```java
Comparator.comparing(X).reversed()
          .thenComparing(Y, Comparator.reverseOrder())
// or: .thenComparing(Comparator.comparing(Y).reversed())
```

or build both keys ascending and reverse the whole comparator once:

```java
Comparator.comparing(X).thenComparing(Y).reversed()
```

The second form is shorter. It is also what `BY_AMOUNT_THEN_DATE_THEN_ID` does in effect: all
three keys ascending, then `max` in place of a reversal. The result matches the SQL's
`DESC, DESC, DESC` because every key is flipped the same way.

## 4. If one implementation divided before rounding and the other after, when exactly would the test fail, and would you notice on typical data?

### When it fails

Suppose one implementation divides the exact total by the count and rounds once, to two
decimals. Suppose the other rounds too early, say to three decimals, and then rounds again to
two. The results differ **only when the exact quotient falls in a narrow band just below a
half-cent**: its third and fourth decimals are between `45` and `49`.

```
11.05 / 11 = 1.004545...

round once, to 2 places:          1.00
round to 3 (1.005), then to 2:    1.01
```

In every other case the two agree. They also agree whenever the division is exact to two
decimals.

### Would you notice?

Probably not. `seedSeptember` has 5 entries and 144.80 / 5 = 28.96 exactly. Any count that
divides the total evenly (1, 2, 4, 5, 10, and so on) usually produces a quotient with at most
four decimals, and usually outside that band. For random totals the chance of hitting the band is
about 1 in 20, so a single fixture passes almost every time, and the bug waits for a real month
with, say, 11 expenses.

That is why `assemble` computes the average **once, in shared code**, from the exact
`totalCents`, with one `divide(..., 2, HALF_UP)`. Each implementation supplies only a total and a
count, which are both exact integers, so they have no rounding step in which to differ. The
50,000-row timing test also asserts equality on random data, and it would catch a gap like this
where the hand-worked fixture cannot.
