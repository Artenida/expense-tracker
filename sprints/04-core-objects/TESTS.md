# Sprint 04 · Tests

`ExpenseTest.java`, `BudgetTest.java`, `ArchitectureTest.java`.

Covers **spec test 3** and completes **spec test 14**.

---

## Expense — construction and rejection

| Test | Proves |
|---|---|
| `validExpenseIsCreated` | The happy path builds and every accessor returns what went in |
| `createGeneratesAUniqueId` | Two `create` calls produce different ids |
| `createStampsCreatedAt` | `createdAt` is within a second of now |
| `descriptionIsTrimmed` | `"  coffee  "` is stored as `"coffee"` |
| `amountIsNormalisedToScaleTwo` | `create(new BigDecimal("24.9"), ...)` stores `24.90` with `.scale() == 2` |

### Rejection — spec test 3 and test 14

```java
@Test
void tomorrowIsRejected() {
    ValidationException thrown = assertThrows(ValidationException.class,
        () -> Expense.create(new BigDecimal("10.00"), Category.OTHER,
                             "future lunch", LocalDate.now().plusDays(1)));
    assertTrue(thrown.errors().stream().anyMatch(m -> m.contains("future")));
}
```

Assert on the **message**, not just the exception type. `assertThrows(ValidationException.class, ...)`
alone would pass if the constructor rejected the expense for the wrong reason
entirely — say, because you broke the description check.

| Rejected input | Expected |
|---|---|
| amount `0.00` | `ValidationException` |
| amount `-5.00` | `ValidationException` |
| amount `24.905` | `ValidationException` |
| description `""` | `ValidationException` |
| description of 101 chars | `ValidationException` |
| date tomorrow | `ValidationException` |
| category `null` | `ValidationException` |

### The multi-error test

```java
@Test
void allProblemsAreReportedAtOnce() {
    ValidationException thrown = assertThrows(ValidationException.class,
        () -> Expense.create(new BigDecimal("-1"), Category.OTHER, "",
                             LocalDate.now().plusDays(1)));
    assertEquals(3, thrown.errors().size());
}
```

This is what makes sprint 17's dialog able to light up three fields at once. If your
constructor throws on the first problem it finds, this test tells you now rather than
thirteen sprints later.

## Expense — the equality contract

The important one:

```java
@Test
void scaleDoesNotAffectEqualityOrHash() {
    Instant now = Instant.now();
    Expense a = Expense.restore("id-1", new BigDecimal("24.9"),  Category.OTHER,
                                "coffee", LocalDate.now(), now);
    Expense b = Expense.restore("id-1", new BigDecimal("24.90"), Category.OTHER,
                                "coffee", LocalDate.now(), now);

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());   // the half everyone forgets
    assertEquals(1, new HashSet<>(List.of(a, b)).size());
}
```

The `HashSet` line is the one that would actually catch a broken contract in practice —
it is the behaviour that breaks when `equals` and `hashCode` disagree, and it is worth
seeing the connection explicitly.

| Test | Proves |
|---|---|
| `differentIdsAreNotEqual` | Identity participates |
| `equalsIsReflexiveAndNullSafe` | `a.equals(a)` is true; `a.equals(null)` is false, not a throw |
| `equalsRejectsOtherTypes` | `a.equals("string")` is false, not a `ClassCastException` |

## Expense — edit

| Test | Proves |
|---|---|
| `editKeepsIdAndCreatedAt` | Only the four editable fields change |
| `editRevalidates` | `edit` with a blank description throws |
| `editDoesNotMutateTheOriginal` | The original's accessors are unchanged afterwards — the immutability claim, actually tested |

## Budget

| Test | Proves |
|---|---|
| `validBudgetIsCreated` | Happy path |
| `zeroLimitIsRejected` | Matches the database `CHECK (limit_cents > 0)` |
| `negativeLimitIsRejected` | |
| `limitCentsConverts` | `Budget.of(OTHER, new BigDecimal("950.00")).limitCents() == 95_000` |

## Architecture

| Test | Proves |
|---|---|
| `onlyStoreImportsJavaSql` | Passes trivially now (there is no `store` yet) and starts earning its keep in sprint 07 |
| `onlyUiImportsJavaFx` | Same, from sprint 13 |

Confirm they can actually fail before trusting them. Temporarily add
`import java.sql.Connection;` to `Expense.java`, run `mvn test`, check that
`onlyStoreImportsJavaSql` fails **and that the message names the file**, then remove it.
A guard test you have never seen fail is a guard test you do not know works.
