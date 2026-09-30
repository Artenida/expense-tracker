# Sprint 03 · Reading check answers

Answers to the four questions at the end of `README.md`.

---

## 1. Why does `Money` have a private constructor when nothing tries to call it?

**Because if you write no constructor, Java writes one for you - and it is public.**

That is the part that surprises people. A class with no constructor is not a class
without a constructor; the compiler silently supplies a public, no-argument one. So
without line 23, this would compile:

```java
Money m = new Money();
```

And what would you have? An object with no fields, no state, and not a single method
worth calling on it - because every method in `Money` is `static` and belongs to the
class rather than to any instance. A meaningless object that looks perfectly
legitimate.

Writing the constructor yourself, as `private`, turns that into a compile error.

**It works together with `final` on line 14.** The two close different doors:

| Keyword | Blocks |
|---|---|
| `private Money() {}` | `new Money()` |
| `final class` | a subclass that declares its own public constructor |

Either alone leaves a way in. Together they make instances genuinely impossible.

**The real benefit is communication, not enforcement.** Nobody was going to write
`new Money()` maliciously. But a reader who opens the file sees the shape and knows
immediately "this is a namespace for functions, not a thing I build". The IDE stops
offering `new Money()` in autocomplete. The class documents its own intent, in a form
the compiler keeps honest.

You will meet the same shape in `Validation`, and in the JDK itself - `java.util.Arrays`
and `java.lang.Math` are built exactly this way.

## 2. `Money.toCents(new BigDecimal("24.905"))` - what happens, and is that a bug?

**It throws `ArithmeticException`. That is deliberate, and it is not a bug.**

Step by step:

```
"24.905"            ->  unscaled 24905, scale 3
.movePointRight(2)  ->  unscaled 24905, scale 1     (= 2490.5)
.longValueExact()   ->  0.5 remains  ->  ArithmeticException
```

The word **`Exact`** in `longValueExact` is the whole mechanism. The plain
`longValue()` would return `2490` and silently drop the half cent. The `Exact` version
refuses to lose anything, and throws instead.

**Why that is right:** half a cent does not exist. If the program accepted `24.905`, it
would have to invent an answer - round to `2490` or to `2491` - and whichever it chose,
money would appear or vanish somewhere with nothing recording it. Over a thousand
expenses that is a total nobody can reconcile.

**Why the exception is allowed to escape** rather than being caught and turned into a
friendly message: a user can never cause it. The route from a text field runs through
`Money.parse`, whose regex allows at most two decimals, and `Validation.amount`. By the
time anything reaches `toCents`, the amount has already been approved.

So a throw here does not mean "the user typed something odd". It means **a programmer
called `toCents` on an amount that never went through validation** - a bug in the code,
which should stop loudly and immediately, with a stack trace pointing at the guilty
line.

This is also why there is no `if` guarding the method. Adding one would duplicate a
check that `longValueExact` already performs perfectly, and a duplicated check is one
more thing that can drift.

> **The general habit:** when a library method already refuses to do the wrong thing,
> let it refuse. Do not wrap it in your own check that does the same job slightly
> differently.

## 3. A test fails with `expected: <24.90> but was: <24.90>`

**It is the scale trap: `assertEquals` uses `.equals()`, and `BigDecimal.equals`
compares the scale as well as the number.**

A `BigDecimal` stores two things - an integer and a count of digits after the point:

```
new BigDecimal("24.90")   unscaled = 2490   scale = 2
new BigDecimal("24.9")    unscaled =  249   scale = 1
```

Same *value*, different *contents*. So:

```java
new BigDecimal("24.90").equals(new BigDecimal("24.9"))      // false
new BigDecimal("24.90").compareTo(new BigDecimal("24.9"))   // 0
```

`equals` says no, `compareTo` says they are numerically equal. `assertEquals` calls
`equals`, so the test fails on two numbers that any human would call identical.

**The fix - and the project's rule:**

```java
// instead of
assertEquals(new BigDecimal("24.90"), expense.amount());

// write
assertEquals(0, new BigDecimal("24.90").compareTo(expense.amount()));
```

Every money assertion in `MoneyTest` is written this way, and `fromCentsProducesScaleTwo`
additionally asserts `.scale() == 2` when the scale itself is part of the contract.

### One correction to the question

The README shows the failure as `expected: <24.90> but was: <24.90>` - identical text on
both sides. That cannot actually happen with two `BigDecimal`s, because `toString()`
prints the scale:

```
new BigDecimal("24.90")   prints  24.90
new BigDecimal("24.9")    prints  24.9
new BigDecimal("24.900")  prints  24.900
```

Different scale always means different text, so the real message reads
`expected: <24.90> but was: <24.9>`.

That is arguably *worse* than the README suggests, because the difference is a single
trailing zero at the end of a line - easy to skim straight past while thinking "but
those are the same number". The lesson is unchanged, and the habit that avoids it is the
same: **compare money with `compareTo(...) == 0`.**

## 4. What would change if `ValidationException` extended `Exception`?

It would become a **checked** exception, and Java's compiler would start enforcing it
everywhere. The change is one word; the consequences spread through the whole codebase.

**The rule for checked exceptions:** every method that can throw one must declare
`throws` in its signature, and every caller must either catch it or declare `throws`
too. It propagates upward until somebody handles it.

**What that would mean here:**

```java
// today
public Expense(BigDecimal amount, ...) { ... }

// if it were checked
public Expense(BigDecimal amount, ...) throws ValidationException { ... }
```

And then, because constructors are called from services, which are called from
controllers, which are called from event handlers, the `throws` clause has to be
repeated at every level or caught somewhere it cannot be dealt with. Every service
method that builds an `Expense` gains it. Every method calling *those* gains it.

**The part that actually hurts: lambdas.** JavaFX event handlers and stream operations
take functional interfaces whose methods declare no checked exceptions:

```java
button.setOnAction(e -> service.save(expense));   // will not compile
                                                  // save() now throws a checked exception
```

`EventHandler.handle` does not declare `throws`, so a checked exception cannot escape
the lambda. You would have to wrap the body in `try`/`catch` inside every lambda, or
write wrapper types whose only job is to convert the checked exception into an unchecked
one - which is the original design, reached by a longer road.

The same problem appears in streams: `.map(row -> Expense.from(row))` stops compiling
for exactly the same reason.

**Why unchecked is the right call here.** Checked exceptions are for conditions a caller
can sensibly *recover* from - a file that might not exist, a network that might be down.
The caller has a real decision to make.

`ValidationException` means something different: *the data was never validated*. The
dialog validates before building anything, and the domain throws only as a last-resort
guard against a caller who skipped that step. There is no meaningful recovery - you
cannot retry your way out of a bug. The right outcome is a stack trace pointing at the
line that forgot to call `Validation`.

Forcing `throws ValidationException` onto hundreds of signatures to describe a condition
that should never occur is ceremony with no payoff. That is the reasoning behind
`ValidationExceptionTest.isUncheckedSoNoSignatureNeedsAThrowsClause` - the name states
the design decision, so nobody "tidies" it into `extends Exception` later.

> **Rule of thumb:** checked for *situations*, unchecked for *bugs*.
