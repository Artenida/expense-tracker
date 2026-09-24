# Sprint 02 · Reading check answers

Answers to the four questions at the end of `README.md`.

---

## 1. Why is `==` safe for two `Category` values when it is wrong for two `String`s?

**Because `==` does not ask "is this the same text?" It asks "is this the same object?"**

Every object lives somewhere in memory. `==` compares those locations. Two objects can
hold identical contents and still sit in different places, and then `==` says false.

**With enums there is only ever one object per constant.** Java builds the seven
`Category` objects once, when the class loads, and there is no way to make another - the
constructor is implicitly private. So "the same object" and "the same value" are the
same question, and `==` cannot be wrong.

**With `String`s that guarantee does not exist.** Anything can build a new `String` at
any time:

```java
String a = "GROCERIES";                 // a literal, reused from a shared pool
String b = "GROCER" + someVariable;     // built at runtime, a brand new object
a == b        // false, even when both read "GROCERIES"
a.equals(b)   // true - compares the characters
```

**This is what makes the bug dangerous:** literals written in your source are stored in
a shared pool, so `"GROCERIES" == "GROCERIES"` *is* true. Your test passes. Then the
real program reads the word out of a CSV file or a database - a fresh object - and the
same comparison quietly returns false. Green tests, broken app.

Two extra reasons `==` is actually *better* than `equals` for enums:

- **The compiler catches mistakes.** `someCategory == someBudgetStatus` will not
  compile - the types are unrelated. `someCategory.equals(someBudgetStatus)` compiles
  fine and silently returns false forever.
- **It cannot throw.** `x == GROCERIES` is safe when `x` is null; `x.equals(GROCERIES)`
  throws `NullPointerException`.

Rule of thumb: **`==` for enums, `.equals()` for everything else.**

## 2. What goes wrong if `EXCEEDED` and `WARNING` are swapped?

**`EXCEEDED` becomes unreachable. Not rare - impossible.**

The two checks, in the correct order:

```java
if (spentCents >= limit)                 return EXCEEDED;   // rule 2
if (spentCents * 100 >= limit * 80)      return WARNING;    // rule 3
```

Look at what rule 3 accepts: everything at 80% or above. That **includes** everything at
100% or above. The WARNING condition is a strictly larger net than the EXCEEDED one - it
catches every case EXCEEDED would have caught, plus more.

So if WARNING is checked first, it answers first, every time. The EXCEEDED line is still
there in the source, still compiles, and can never run for any input at all.

What the user sees: spend 150% of your grocery budget and the app says **"close"** with
an orange bar, instead of **"over"** with a red one. The one moment the app most needs to
raise its voice is the moment it goes quiet.

```
spent = 15_000, limit = 10_000   (150%)

correct order:   15000 >= 10000            -> EXCEEDED  "over"
swapped order:   15000*100 >= 10000*80     -> WARNING   "close"   <- wrong
```

This is why the spec insists on **early returns** rather than `else if`. With early
returns the order is visible on the page, in sequence. With a chain of `else if` the
ordering is implied, and someone reformatting the method later will not realise the
sequence is load-bearing.

`BudgetStatusTest` guards it: the rows `10000 -> EXCEEDED` and `15000 -> EXCEEDED` both
fail the moment the checks are swapped.

## 3. Someone hand-edits the database and sets a category to `FOOD`

**Where it is noticed:** in the store layer (sprint 07), the moment that row is read
back. The store pulls the text out of the column and hands it to `Category.parse(...)`,
which trims it, uppercases it, and asks `valueOf("FOOD")` for a matching constant. There
is none, so it throws.

**What the user sees:**

```
unknown category 'FOOD' - expected one of:
GROCERIES, TRANSPORT, HOUSING, LEISURE, HEALTH, EDUCATION, OTHER
```

That wording is the entire reason `parse` exists instead of calling `valueOf` directly.
`valueOf`'s own message is `No enum constant com.expensetracker.domain.Category.FOOD` -
fine for a programmer reading a stack trace, useless to a person looking at a window.

**The idea underneath it:** the database does not know the enum exists. SQLite stores a
column of text; it will happily accept `FOOD`, `banana`, or an empty string. The seven
valid values are a rule that lives **only in Java**.

So the rule has to be enforced at the **boundary** - the exact point where outside text
becomes an inside object. `parse` is that boundary, and there is only one of it. Every
route into the program (database rows, CSV import, typed input) funnels through the same
method, so they cannot disagree about what counts as valid.

**Why fail loudly rather than substitute `OTHER`?** Because silently rewriting `FOOD` to
`OTHER` would hide the corruption and quietly produce wrong totals forever. A visible
error is a bad afternoon; silently wrong money is a bug you never find.

Note also *when* it is caught: on **read**, not on write. Nothing stopped the bad value
going in, because that edit bypassed the application entirely.

## 4. Why does `suggestedLimitCents` return `long` and not `BigDecimal`?

**Because it is not a decimal number. It is a count.**

A cent is the smallest unit of money there is - you cannot have 0.4 of one. So "40,000
cents" is a whole number of indivisible things, exactly like "40,000 apples". `long`
holds that perfectly, with no rounding, because there is nothing to round.

`BigDecimal` exists to handle numbers *with a decimal point* carefully. Storing a count
of cents in one adds machinery you have no use for:

| | `long` cents | `BigDecimal` |
|---|---|---|
| Exact? | yes, always | yes, if you configure rounding right |
| `spent * 100 >= limit * 80` | one readable line | three lines of method calls |
| `40_000 == 40_000` | true | `new BigDecimal("1.10").equals(new BigDecimal("1.1"))` is **false** - same value, different scale |
| Cost | a primitive, no allocation | an object built every time |

That third row is a genuine trap. `BigDecimal` treats `1.10` and `1.1` as different
because it remembers how many decimal places you wrote. Comparisons need
`compareTo() == 0` instead of `equals()`, and forgetting is a real bug. Integers have no
such surprise.

**And PLAN.md's rule is not being broken.** "Money is `BigDecimal` in Java" governs the
places where money behaves like a decimal - parsing what a user typed (`"12.34"`),
formatting for display, splitting or applying percentages where a fraction of a cent
can appear. That is the *human-facing* layer.

Storage and decisions use exact integer cents. It is the same split sprint 02 states as
its main lesson:

> **Round for humans, never for branches.**

`long` cents is the branch side. `BigDecimal` is the human side. `suggestedLimitCents`
feeds `BudgetStatus.of`, which is pure branching - so `long` is the honest type.

**One answer that is wrong for both:** `double`. Binary floating point cannot represent
`0.10` exactly, so money in a `double` drifts - `0.1 + 0.2` is famously `0.30000000000000004`.
Never use it for currency.
