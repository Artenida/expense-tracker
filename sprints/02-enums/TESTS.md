# Sprint 02 · Tests

`src/test/java/com/expensetracker/domain/CategoryTest.java` and `BudgetStatusTest.java`

---

## Category

| Test | Proves |
|---|---|
| `parseAcceptsExactName` | `parse("GROCERIES") == GROCERIES` |
| `parseIsCaseInsensitiveAndTrims` | `parse("  groceries ") == GROCERIES` |
| `parseRejectsUnknownAndListsValidNames` | Throws `IllegalArgumentException`; message contains `FOOD` **and** all seven valid names |
| `parseRejectsBlank` | `""`, `"   "` and `null` all throw |
| `everyCategoryHasAPositiveSuggestedLimit` | Loops `values()`; no constant was added with a forgotten or zero limit |
| `everyCategoryHasANonBlankDisplayName` | Same, for the display name |

The last two are the kind of test worth writing once and forgetting: they are the only
thing that will catch you adding an eighth category in six months and mistyping its
limit.

## BudgetStatus — the boundaries

These four are the point of the sprint. Use a limit of **10 000 cents (100.00)** so the
arithmetic is easy to check by eye.

| Spent | Expected | Why this value |
|---|---|---|
| `7_999` (79.99%) | `OK` | Just below the threshold |
| `8_000` (80.00%) | `WARNING` | Exactly the threshold — the spec says this is WARNING |
| `9_999` (99.99%) | `WARNING` | Just below the limit |
| `10_000` (100%) | `EXCEEDED` | Exactly the limit — the spec says this is EXCEEDED |
| `15_000` (150%) | `EXCEEDED` | Proves rule 2 is checked before rule 3 |
| `0` | `OK` | An untouched budget |

Write them as a single `@ParameterizedTest` with a `@CsvSource` rather than six
methods — it makes the table above readable directly in the source:

```java
@ParameterizedTest
@CsvSource({
    " 7999, OK",
    " 8000, WARNING",
    " 9999, WARNING",
    "10000, EXCEEDED",
    "15000, EXCEEDED",
    "    0, OK"
})
void thresholds(long spentCents, BudgetStatus expected) {
    assertEquals(expected, BudgetStatus.of(spentCents, OptionalLong.of(10_000)));
}
```

JUnit converts the second column from `String` to `BudgetStatus` automatically, because
it knows how to parse an enum from its name.

## BudgetStatus — the rest

| Test | Proves |
|---|---|
| `noLimitMeansNoBudget` | `of(5_000, OptionalLong.empty()) == NO_BUDGET`, whatever was spent |
| `zeroLimitIsRejected` | `of(0, OptionalLong.of(0))` throws `IllegalArgumentException` |
| `negativeSpendIsRejected` | `of(-1, OptionalLong.of(100))` throws |
| `everyStatusHasADistinctCssClass` | Collect `cssClass()` for `values()` into a `Set`; assert size 4. Catches a copy-paste that gives two statuses the same colour. |

## The test that proves the trap is real

Worth writing deliberately, because it is the whole reason this sprint exists:

```java
@Test
void aValueJustBelowTheThresholdIsNotWarning() {
    // 79.996% — rounds to 80.00 under HALF_UP, but is not 80%
    assertEquals(BudgetStatus.OK, BudgetStatus.of(79_996, OptionalLong.of(100_000)));
}
```

Implement `of` with the `BigDecimal`-and-round approach from README.md and watch this
fail. Then implement it with cross-multiplication and watch it pass. Five minutes, and
you will not make the rounding mistake again.
