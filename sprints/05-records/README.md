# Sprint 05 · Records and the summary shapes

**Time:** ~1.5 hours · **Prerequisites:** 04 · **Produces:** `CategoryTotal`, `MonthSummary`, `ExpenseFilter`

---

## Purpose

Sprint 04 built objects with *identity* — an `Expense` is a particular thing that
exists, gets edited, and persists. This sprint builds objects that are purely *values*:
a `MonthSummary` is not a thing that exists in the world, it is the answer to a
question. Ask the same question twice and you should get two summaries that are equal.

Java has a dedicated form for this, and it removes about 80 lines of boilerplate per
class. It also introduces one sharp edge that will bite in sprint 11 if you do not
handle it here.

The last class, `ExpenseFilter`, is the one the left-hand side of the window produces
and the store consumes. It is worth noticing that it exists at all: without it, "find
expenses" would take two loose parameters that every layer passes along individually,
and adding a third filter later would mean changing five signatures.

## New concepts

### 1. What `record` generates

```java
public record CategoryTotal(Category category, BigDecimal spent, ...) {}
```

One line gives you: a `private final` field per component, a canonical constructor, an
accessor per component named exactly like the component, plus `equals`, `hashCode` and
`toString`. All of it derived from the component list, so it cannot fall out of step
when you add a field — which is exactly the failure mode sprint 04 warned about with
`Expense.hashCode`.

Records are implicitly `final` and cannot extend anything. That is the point: a record
says "this is the data, all of it, and nothing else."

### 2. The compact constructor

You still need validation and normalisation. A record gives you a shorthand:

```java
public record MonthSummary(YearMonth yearMonth, ..., List<CategoryTotal> totals) {

    public MonthSummary {                         // no parameter list, no assignments
        Objects.requireNonNull(yearMonth);
        totals = List.copyOf(totals);             // reassigning the parameter
    }
}
```

No parameters, no braces around a signature, no `this.x = x` lines. You mutate the
*parameters*, and the compiler assigns the final values to the fields after your code
runs. Reading it for the first time is confusing; the mental model that helps is
"everything in here happens **before** the fields are set".

### 3. Records are only shallowly immutable

This is the one that surprises people:

```java
List<CategoryTotal> mutable = new ArrayList<>();
MonthSummary summary = new MonthSummary(..., mutable);
mutable.clear();                      // the summary's list is now empty too
```

The *reference* is final. What it points at is not. `List.copyOf(totals)` in the compact
constructor makes a genuinely immutable copy and closes the hole. It also rejects
`null` elements, which is a free bonus.

### 4. Record equality inherits `BigDecimal`'s scale problem

The generated `equals` calls `Objects.equals` on every component — so for a
`BigDecimal` component it calls `BigDecimal.equals`, which is **scale-sensitive**,
which is the trap from sprint 03.

This is not theoretical. Spec test 8 requires:

```java
assertEquals(summaryViaSql(month), summaryViaStream(month));
```

The SQL version sums integer cents and converts once at the end: scale 2. The stream
version adds `BigDecimal`s that came from the mapper: also scale 2, *if* nothing in the
chain changed it — and `BigDecimal.ZERO` used as a stream identity has scale **0**. A
month total of zero then compares unequal to a month total of zero, and you will spend
an evening on it.

**Fix it here, once:** every `BigDecimal` component gets `.setScale(2, HALF_UP)` in the
compact constructor. After that, two summaries of the same data are equal regardless of
how they were computed.

### 5. `Optional` as a record component

`Optional<Expense> largest` and `Optional<BigDecimal> limit`. There is a well-known
argument that `Optional` belongs in return types and not in fields — it is not
serialisable and it costs an allocation. Here it earns its place: the project's rule is
that no public method returns `null`, a record's accessor *is* a public method, and a
month with no expenses genuinely has no largest one. The alternative is a `null`
component and a documented convention, which is exactly what the rule exists to prevent.

## What you build

- `CategoryTotal` — one category's line in the summary panel
- `MonthSummary` — everything the right-hand panel displays
- `ExpenseFilter` — what the left-hand side produces

Nothing computes a summary yet. That is sprint 11. This sprint defines the *shape* of
the answer, which is why it comes first: sprint 11 is much easier when the target is
already fixed.

## Definition of done

- [ ] `MonthSummary.empty(YearMonth)` returns zeroes, an empty list and an empty
      `Optional` — never a throw, never a `null`
- [ ] Two `MonthSummary` values built from the same data are `equals`, even when one
      was assembled from `BigDecimal.ZERO` and the other from `new BigDecimal("0.00")`
- [ ] Mutating the list you passed to the constructor does not change the summary
- [ ] `ExpenseFilter.from()` and `to()` give the first and last day of the month

---

## Reading check

1. The compact constructor has no parameter list and no assignments. Where do the
   fields get set?
2. `List.copyOf` versus `new ArrayList<>(totals)` versus
   `Collections.unmodifiableList(totals)` — what does each protect against, and which
   one still leaves a hole?
3. `MonthSummary` has both `totalSpent` and `totals`. `totalSpent` could be derived by
   summing `totals`. Why store it separately — and what new failure does that allow?
4. Why does `ExpenseFilter` hold a `YearMonth` rather than the two `LocalDate` bounds
   the SQL actually needs?
