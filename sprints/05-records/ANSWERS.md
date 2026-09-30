# Sprint 05 · Reading check answers

Answers to the four questions at the end of `README.md`.

---

## 1. The compact constructor has no parameter list and no assignments. Where do the fields get set?

**At the end of it. The compiler adds the assignments after your last line.**

What you write:

```java
public MonthSummary {
    Objects.requireNonNull(yearMonth, "yearMonth");
    totalSpent = totalSpent.setScale(2, RoundingMode.HALF_UP);
    totals = List.copyOf(totals);
}
```

What the compiler actually produces is the full **canonical constructor**, roughly:

```java
public MonthSummary(YearMonth yearMonth, BigDecimal totalSpent, ..., List<CategoryTotal> totals,
                    Optional<Expense> largest) {
    // your code, unchanged
    Objects.requireNonNull(yearMonth, "yearMonth");
    totalSpent = totalSpent.setScale(2, RoundingMode.HALF_UP);
    totals = List.copyOf(totals);

    // added by the compiler, after your code
    this.yearMonth = yearMonth;
    this.totalSpent = totalSpent;
    ...
    this.totals = totals;
    this.largest = largest;
}
```

Two things follow from that:

- **Inside the compact constructor, the names are the parameters, not the fields.**
  `totalSpent = ...` reassigns the parameter, which is allowed. The field is not touched
  until the end.
- **Whatever each parameter holds at the closing brace is what gets stored.** That is why
  reassigning `totals` to its copy works: the compiler stores the copy, and the caller's
  original list is never saved.

You are **not allowed** to write `this.totalSpent = ...` in a compact constructor. It is a
compile error, precisely so there is one place where fields get assigned and it is always
the same.

And if the constructor throws - `requireNonNull` failing, say - the compiler-added
assignments never run, and no object is created at all. Same guarantee as `Expense`'s
private constructor: there is never a half-built value.

## 2. `List.copyOf` versus `new ArrayList<>(totals)` versus `Collections.unmodifiableList(totals)`

Each one protects against something different. The question is which of two directions
it covers:

- **In:** the caller changes the list they passed in, *after* building the summary.
- **Out:** someone changes the list they got back from `summary.totals()`.

| | Makes a copy? | Unmodifiable? | Protects **in** | Protects **out** | Allows `null` elements |
|---|---|---|---|---|---|
| `List.copyOf(totals)` | yes | yes | yes | yes | no - throws |
| `new ArrayList<>(totals)` | yes | no | yes | **no** | yes |
| `Collections.unmodifiableList(totals)` | **no** - a view | yes | **no** | yes | yes |

### `new ArrayList<>(totals)` - the copy you can still change

A new, separate list, so clearing the caller's list does nothing to it. But it is an
ordinary `ArrayList`, and the record's accessor hands out the field itself:

```java
summary.totals().clear();     // succeeds - and the "immutable" summary is now empty
```

### `Collections.unmodifiableList(totals)` - the hole

This is the one that looks safe and is not. It does **not copy**. It wraps the caller's
list in a read-only *view*: calling `add` on the view throws, but the view reads straight
through to the original list underneath.

```java
List<CategoryTotal> mine = new ArrayList<>(List.of(groceries));
MonthSummary summary = new MonthSummary(..., Collections.unmodifiableList(mine), ...);

summary.totals().add(x);      // throws UnsupportedOperationException - looks immutable
mine.clear();                 // succeeds
summary.totals().size();      // 0 - the summary changed
```

Nobody holding the summary can modify it, but the caller who built it still can, through
the reference they kept. That is the hole: it protects **out** and leaves **in** wide
open. It is the dangerous one because the `add` throwing makes it *look* safe in a quick
test.

### `List.copyOf` - both directions

A copy, so the caller's list is disconnected (**in**). Unmodifiable, so what the accessor
returns cannot be changed (**out**). The two tests check one direction each:
`mutatingTheSourceListDoesNotAffectTheSummary` and `theReturnedListCannotBeModified`.

Two bonuses:

- **It rejects `null` elements**, so a `MonthSummary` can never contain a hole that
  crashes the panel later.
- **It does not copy when it does not need to.** If the list passed in is already an
  unmodifiable list made by `List.of` or `List.copyOf`, it may return it unchanged. That
  is safe, because nobody can modify it - so `empty()`'s `List.of()` costs nothing.

## 3. `totalSpent` could be derived by summing `totals`. Why store it separately - and what new failure does that allow?

### Why store it

- **It is the thing sprint 11 checks.** The SQL version gets it from one
  `SUM(amount_cents)` over the whole month. The stream version adds up the expenses.
  Holding it as its own component means spec test 8 compares the two *computed* totals,
  rather than both being re-derived from the same list, which would hide a mistake in
  either query.
- **Convenience and cost.** It is what the panel shows in large type. A record is built
  once and read many times, so adding up the list on every read would do the same sum
  again and again.

### The failure it allows

**The two can disagree.** Nothing in the compact constructor checks that
`totalSpent` equals the sum of `totals`' `spent` values. This compiles and constructs
without complaint:

```java
new MonthSummary(september,
        new BigDecimal("500.00"),          // totalSpent says 500
        ..., List.of(groceries_236_40),    // totals add up to 236.40
        ...);
```

The panel would then show a headline of 500.00 above rows adding up to 236.40, and nothing
would say which one is wrong. Storing the same fact twice means two copies that can
drift - the problem records solved for `equals` and `hashCode`, back again in the data.

### Why not check it in the constructor

It could be checked, and cleanly: every `spent` is at scale 2, so adding them up is exact
and a comparison with `compareTo` would have no rounding to argue about. It is left out
for now:

- `SPEC.md` does not ask for it, and the compact constructor's job so far is to check
  each component on its own, not to relate components to each other.
- The place that can get it wrong is sprint 11's two computations, and spec test 8
  catches that: if either one adds up incorrectly, the two summaries differ.

So the risk is real, noted here, and covered by a test rather than by a constructor rule.
If sprint 11 shows it is worth enforcing, the compact constructor is the place to add it -
one `reduce` over `totals` and one `compareTo`.

## 4. Why does `ExpenseFilter` hold a `YearMonth` rather than the two `LocalDate` bounds the SQL needs?

**Because a `YearMonth` can only mean a whole month, and a pair of dates can mean anything.**

### It makes an invalid filter impossible

The app only ever filters by month. With two dates, every one of these would be a
perfectly legal `ExpenseFilter`:

```java
new ExpenseFilter(LocalDate.of(2026, 9, 1),  LocalDate.of(2026, 9, 31));  // no Sept 31: throws, somewhere
new ExpenseFilter(LocalDate.of(2026, 9, 1),  LocalDate.of(2026, 9, 29));  // missing a day
new ExpenseFilter(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 8, 1));   // backwards
```

The summary would then be "the summary for September" computed over something that is not
September. With a `YearMonth` none of these can be written. It is sprint 04's idea again:
make the invalid state impossible to represent, rather than checking for it.

### The month-end calculation happens once, in the JDK

Somebody has to know that September has 30 days, February 28 - or 29 in 2024 - and
December 31. If callers built the dates, every caller would have to get that right: the
UI, the service, the CSV export. With a `YearMonth`, `atEndOfMonth()` does it in one place,
and the JDK's version is already correct for every year. The two February tests in
`ExpenseFilterTest` are there to point at exactly that.

### It is what the user chose and what the summary is about

The user picks *September 2026* in the window, not two dates. `MonthSummary` has a
`yearMonth` component. Keeping the filter in the same terms means the UI, the filter and
the summary all talk about the same thing, and converting to dates is the store's
concern, at the last moment.

### The dates are still there when the SQL needs them

`from()` and `to()` produce them on demand, for sprint 08's `BETWEEN ? AND ?`. Both are
inclusive, as `BETWEEN` is, and because dates are stored as ISO text
(`2026-09-01`), comparing them as strings gives the same order as comparing them as dates.

**The general habit:** store the value in the terms that mean something - here, a month -
and *derive* the representation a particular layer needs. Going the other way, from two
dates back to "which month is this?", is guesswork.
