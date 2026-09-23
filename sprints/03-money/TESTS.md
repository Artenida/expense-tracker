# Sprint 03 · Tests

`MoneyTest.java` and `ValidationTest.java` in `src/test/java/com/expensetracker/domain/`.

These cover **tests 1, 2 and 14** from the specification's required list.

---

## Money — conversion

| Test | Proves |
|---|---|
| `bigDecimalArithmeticIsExact` | `new BigDecimal("0.1").add(new BigDecimal("0.2"))` compares equal to `0.30` — spec test 1 |
| `toCentsConvertsWholeAndFractional` | `24.90 → 2490`, `0.05 → 5`, `1000.00 → 100000` |
| `fromCentsProducesScaleTwo` | `fromCents(2490)` equals `24.90` *and* `.scale() == 2` |
| `threeDecimalsAreRejected` | `toCents(new BigDecimal("24.905"))` throws `ArithmeticException` |

### The round-trip test — spec test 2

The specification asks for a hundred random amounts. This is a **property test**: rather
than checking known inputs, it asserts a relationship that must hold for all of them.

```java
@Test
void amountsSurviveARoundTrip() {
    Random random = new Random(20260922);   // fixed seed — reproducible failures
    for (int i = 0; i < 100; i++) {
        long cents = random.nextInt(1, 100_000_000);
        BigDecimal amount = Money.fromCents(cents);
        assertEquals(cents, Money.toCents(amount),
            () -> "round trip lost precision for " + cents);
    }
}
```

Two details worth copying into your own tests later:

- **Seed the `Random`.** An unseeded one gives you a test that fails on Tuesdays and
  passes when you rerun it, with no way to reproduce the failing input.
- **The lambda message.** `assertEquals(a, b, () -> "...")` builds the string only when
  the assertion fails. On 100 passing iterations that is 100 strings not built.

## Money — parsing

| Input | Expected |
|---|---|
| `"24.90"` | `24.90`, scale 2 |
| `"24.9"` | `24.90`, scale 2 |
| `"24"` | `24.00`, scale 2 |
| `"  24.90  "` | `24.90` (trimmed) |
| `"1.234"` | throws |
| `"12,50"` | throws — **the locale decision** |
| `"-2"` | throws |
| `"abc"` | throws |
| `""` | throws |
| `"1e5"` | throws |

A `@ParameterizedTest` with `@ValueSource(strings = {...})` handles the rejection cases
in one method.

## Money — formatting

| Test | Proves |
|---|---|
| `formatAlwaysShowsTwoDecimals` | `format(new BigDecimal("24"))` is `"24.00"`, not `"24"` |
| `formatNeverUsesScientificNotation` | `format(new BigDecimal("1E+2"))` is `"100.00"` |

## Validation — spec test 14

The specification requires that `"abc"`, `""`, `"1.234"` and `"-2"` each produce a
validation message and no domain object. The second half is sprint 04's job; here just
prove the message appears.

```java
@ParameterizedTest
@ValueSource(strings = {"abc", "", "   ", "1.234", "-2", "12,50", "0", "0.00"})
void badAmountsProduceAMessage(String raw) {
    assertFalse(Validation.amount(raw).isEmpty());
}
```

Note `"0"` and `"0.00"` are in that list. They pass the *format* check in `Money.parse`
and fail the *business* check in `Validation.amount` — a good demonstration of why the
two live in different classes.

| Test | Proves |
|---|---|
| `validAmountProducesNoMessages` | `Validation.amount("24.90")` is empty |
| `nullAmountIsReported` | `Validation.amount(null)` returns a message rather than throwing `NullPointerException` |
| `descriptionOf100CharsIsAccepted` | The boundary is inclusive |
| `descriptionOf101CharsIsReported` | …and 101 is not. The message contains `101`. |
| `blankDescriptionIsReported` | `"   "` is not a description |
| `todayIsAccepted` | `Validation.date(LocalDate.now())` is empty — "no future" does not mean "no today" |
| `tomorrowIsReported` | Spec test 3, at the validator level |

## The `null` habit

Every validator is tested with `null`. It looks like padding and it is not: these
methods are called from a dialog where a field may genuinely be empty, and a validator
that throws `NullPointerException` instead of reporting "amount is required" turns a
normal user action into a crash. Sprint 17 depends on this holding.
