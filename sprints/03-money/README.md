# Sprint 03 · Money and validation

**Time:** ~2 hours · **Prerequisites:** 02 · **Produces:** `Money`, `Validation`, `ValidationException`

---

## Purpose

Two small classes that everything else leans on.

`Money` is the **single place** where a currency amount changes representation. Java
holds `BigDecimal`, the database holds integer cents, a text field holds a `String`.
Three representations, six possible conversions — all of them in one class, so there is
exactly one file to check when a total comes out wrong.

`Validation` is the answer to a question the specification raises and does not fully
answer: the dialog must tell you "amount must be greater than zero" *as you type*, and
the domain must **throw** if you somehow construct an invalid `Expense` anyway. Write
those as two separate implementations and they will disagree. Write the rule once, in a
form that *reports*, and let the domain be the one that turns a report into a throw.

## New concepts

### 1. Why `double` cannot hold money

```java
0.1 + 0.2                                        // 0.30000000000000004
new BigDecimal("0.1").add(new BigDecimal("0.2")) // 0.3
```

A `double` stores a binary fraction. `0.1` is not representable in binary any more than
`1/3` is representable in decimal, so it is stored as the nearest available value and
the error compounds with every operation. Over a thousand expenses it is visible in the
total.

`BigDecimal` stores an integer plus a **scale** — a count of digits after the point.
`24.90` is the integer `2490` with scale `2`. Nothing is approximated, so nothing drifts.

### 2. Scale is part of a `BigDecimal`'s identity

This catches everybody once:

```java
new BigDecimal("24.9").equals(new BigDecimal("24.90"))       // false
new BigDecimal("24.9").compareTo(new BigDecimal("24.90"))    // 0
```

`equals` compares unscaled value **and** scale: `249` scale 1 versus `2490` scale 2.
`compareTo` compares numeric value only.

The rule for this project: **compare money with `compareTo(...) == 0`, never with
`equals`.** It matters in sprint 04, where `Expense.equals` has to decide whether two
expenses are the same, and again in every test that asserts an amount.

### 3. A utility class

`Money` has no state. Every method is `static`. There is never a reason to construct
one, so make that impossible:

```java
public final class Money {
    private Money() {}   // no instances
    ...
}
```

`final` stops subclassing, the private constructor stops instantiation. Together they
say "this is a namespace for functions, not a thing". You will meet the same shape in
`Validation`.

### 4. Reporting versus throwing

```java
// Validation — reports. Returns empty when valid.
public static List<String> amount(String raw)

// Expense constructor — throws.
List<String> errors = Validation.amount(raw);
if (!errors.isEmpty()) throw new ValidationException(errors);
```

The dialog in sprint 17 calls the first one on every keystroke and paints the messages
next to the field. The domain calls the same one and throws. One rule, two reactions.

This is what the specification means by "validation happens twice, deliberately" —
and why it is not duplicated logic.

## The decision you are implementing

**`12,50` is rejected.** Only `^\d{1,9}(\.\d{1,2})?$` is accepted.

A `TextField` hands you a `String` typed by a human in some locale. `new
BigDecimal("12,50")` throws `NumberFormatException`; `NumberFormat.getInstance()` would
accept it on a European locale and reject it on a US one — so the same build would
behave differently on two machines. Pinning the format removes the ambiguity. If you
later want comma input, it becomes a deliberate normalisation step with its own test,
not an accident of the operating system's region setting.

## What you build

- `Money` — four static methods, no state
- `Validation` — three static methods, each returning a list of messages
- `ValidationException` — unchecked, carries the list

## Definition of done

- [ ] `Money.toCents(new BigDecimal("24.90")) == 2490`
- [ ] `Money.fromCents(2490)` equals `24.90` *and* has scale 2
- [ ] A hundred random amounts survive a round trip with no loss
- [ ] `"abc"`, `""`, `"1.234"`, `"-2"` and `"12,50"` each produce a message
- [ ] `ValidationException` is unchecked, so no method signature needs `throws`

---

## Reading check

1. Why does `Money` have a private constructor when nothing tries to call it?
2. `Money.toCents(new BigDecimal("24.905"))` — what happens, and is that a bug?
3. A test asserts `assertEquals(new BigDecimal("24.90"), someExpense.amount())` and
   fails with `expected: <24.90> but was: <24.90>`. What is going on?
4. `ValidationException` extends `RuntimeException` rather than `Exception`. What would
   change throughout the codebase if it extended `Exception` instead?
