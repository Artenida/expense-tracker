# Sprint 03 · Build specification

All three files in `src/main/java/com/expensetracker/domain/`.

---

## `Money.java`

```java
public final class Money {
    private Money() {}

    public static long       toCents(BigDecimal amount);
    public static BigDecimal fromCents(long cents);
    public static BigDecimal parse(String raw);
    public static String     format(BigDecimal amount);
}
```

### `toCents(BigDecimal)`

```java
return amount.movePointRight(2).longValueExact();
```

`movePointRight(2)` turns `24.90` (unscaled `2490`, scale `2`) into `2490` with scale
`0`. `longValueExact()` then throws `ArithmeticException` if there is any fractional
part left — which is exactly what you want for `24.905`. That throw is the check; do
not add a separate one.

Let the `ArithmeticException` escape. Callers reach this method only with an amount
that `Validation` already approved, so a throw here means a bug upstream, not bad user
input.

### `fromCents(long)`

```java
return BigDecimal.valueOf(cents, 2);
```

The two-argument `valueOf(long unscaledValue, int scale)` builds the value directly —
`2490` with scale `2` is `24.90`. Do **not** write `BigDecimal.valueOf(cents / 100.0)`;
that routes through a `double` and reintroduces everything this class exists to prevent.

### `parse(String)`

| Step | Rule |
|---|---|
| 1 | `raw == null` → `IllegalArgumentException` |
| 2 | Trim |
| 3 | Must match `^\d{1,9}(\.\d{1,2})?$` — otherwise `IllegalArgumentException` |
| 4 | `new BigDecimal(trimmed).setScale(2)` |

Compile the pattern once into a `private static final Pattern`. Compiling it on every
keystroke in sprint 17's dialog would be wasteful, and a `static final Pattern` is the
idiomatic way to say "this is a constant".

`setScale(2)` with no rounding mode is deliberate: the regex already guarantees at most
two decimals, so there is nothing to round. If that assumption ever breaks, this line
throws rather than silently rounding.

Note `parse` does **not** check "greater than zero". The regex has no sign, so negatives
are rejected already, but `"0.00"` parses fine. Zero is rejected by `Validation.amount`,
because "must be greater than zero" is a *business* rule and belongs there, while "must
look like a number" is a *format* rule and belongs here.

### `format(BigDecimal)`

```java
return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
```

`toPlainString()` rather than `toString()` — `toString()` can produce scientific
notation (`1E+2`) for some values, which is never what you want in a table cell.

This is the **only** place rounding happens in the whole project. Rounding for display
is fine. Rounding before a comparison or a sum is the bug from sprint 02.

---

## `ValidationException.java`

```java
public class ValidationException extends RuntimeException {

    private final List<String> errors;

    public ValidationException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() { return errors; }
}
```

Three things to notice:

- **`extends RuntimeException`**, so it is unchecked. No method in the project needs a
  `throws` clause for it. A checked exception here would force `throws
  ValidationException` onto every constructor and every service method that calls one —
  noise for a condition that means "programmer error upstream".
- **`super(String.join(...))`** gives the exception a readable `getMessage()` for logs
  and stack traces, while `errors()` keeps the structured list for the dialog to render
  field by field.
- **`List.copyOf`** makes an immutable copy. Without it, a caller could mutate the list
  after throwing and change what the catch block sees.

---

## `Validation.java`

```java
public final class Validation {
    private Validation() {}

    public static List<String> amount(String raw);
    public static List<String> description(String raw);
    public static List<String> date(LocalDate date);
}
```

**Contract for all three: return an empty list when valid.** Never `null`, never a
`boolean`, never a thrown exception. Callers decide what to do with the messages.

Collect into a local `List<String>` and return it — a method may legitimately report
more than one problem at once.

### `amount(String raw)`

| Condition | Message |
|---|---|
| `null` or blank | `amount is required` |
| does not match the pattern | `amount must be a number with at most two decimals, for example 24.90` |
| parses but is `<= 0` | `amount must be greater than zero` |

Delegate the pattern check to `Money.parse` inside a `try`/`catch`, rather than
duplicating the regex here. Two copies of a regex is two things to keep in step.

### `description(String raw)`

| Condition | Message |
|---|---|
| `null` or blank | `description is required` |
| trimmed length > 100 | `description must be 100 characters or fewer (was 137)` |

Include the actual length in the message. "Too long" tells the user nothing they did
not already suspect.

### `date(LocalDate date)`

| Condition | Message |
|---|---|
| `null` | `date is required` |
| after `LocalDate.now()` | `date cannot be in the future` |

> **A note on `LocalDate.now()`.** Reading the system clock inside a validator makes the
> method untestable at a fixed point in time — a test for "31 December is accepted"
> behaves differently on 30 December. The proper fix is to pass a `java.time.Clock` in.
> This project does not need it (the only rule is "not in the future", which is stable),
> so keep `LocalDate.now()` and know the trade-off. It is a real pattern you will meet
> in code that has date rules more interesting than this one.
