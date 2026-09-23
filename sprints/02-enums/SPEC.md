# Sprint 02 · Build specification

Both files live in `src/main/java/com/expensetracker/domain/`.

---

## `Category.java`

```java
package com.expensetracker.domain;

public enum Category {
    GROCERIES ("Groceries",  40_000),
    TRANSPORT ("Transport",  12_000),
    HOUSING   ("Housing",   90_000),
    LEISURE   ("Leisure",    15_000),
    HEALTH    ("Health",     10_000),
    EDUCATION ("Education",  20_000),
    OTHER     ("Other",      10_000);

    // fields, constructor, accessors, parse()
}
```

### Required members

| Member | Signature | Behaviour |
|---|---|---|
| Constructor | `Category(String displayName, long suggestedLimitCents)` | Implicitly private. Assigns both `final` fields. |
| Accessor | `public String displayName()` | Returns the human-readable name. |
| Accessor | `public long suggestedLimitCents()` | Returns the default monthly limit in cents. |
| Factory | `public static Category parse(String raw)` | See below. |

### `parse` — the rules

This is what the store calls when reading a row, and what the CSV importer calls on
each line. It must fail with a message a person can act on.

- Trim the input, then uppercase it, then `valueOf`.
- On failure, throw `IllegalArgumentException` whose message **lists all valid names**:
  `unknown category 'FOOD' — expected one of: GROCERIES, TRANSPORT, HOUSING, LEISURE,
  HEALTH, EDUCATION, OTHER`
- A `null` or blank input is an error too, with the same style of message.

Build the list with a stream rather than hardcoding the text, so it cannot go stale:

```java
String valid = Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
```

> **Why not just let `valueOf` throw?** Its message is
> `No enum constant com.expensetracker.domain.Category.FOOD`. That is written for a
> programmer reading a stack trace, not for someone who just imported a CSV. Sprint 12
> shows this message directly to the user.

---

## `BudgetStatus.java`

```java
package com.expensetracker.domain;

import java.util.OptionalLong;

public enum BudgetStatus {
    OK          ("status-ok",          "on track"),
    WARNING     ("status-warning",     "close"),
    EXCEEDED    ("status-exceeded",    "over"),
    NO_BUDGET   ("status-no-budget",   "no budget");

    // fields, constructor, accessors, of()
}
```

### Required members

| Member | Signature | Behaviour |
|---|---|---|
| Accessor | `public String cssClass()` | The style class sprint 16 applies to the progress bar. |
| Accessor | `public String label()` | Short text shown next to the bar. |
| Factory | `public static BudgetStatus of(long spentCents, OptionalLong limitCents)` | See below. |

### `of` — the rules, in this exact order

```
1. limitCents is empty            → NO_BUDGET
2. spentCents >= limit            → EXCEEDED
3. spentCents * 100 >= limit * 80 → WARNING
4. otherwise                      → OK
```

**The order is load-bearing.** Spending 150% of a budget satisfies both rule 2 and
rule 3; checking `EXCEEDED` first is what makes the result right. Write the checks as
early returns rather than `else if` — it makes the ordering visible instead of implied.

Guard the inputs:

- `limitCents` present but `<= 0` → `IllegalArgumentException`. A zero limit would make
  rule 3 read `spent * 100 >= 0`, which is true for everything.
- `spentCents < 0` → `IllegalArgumentException`.

### Why `OptionalLong` rather than `Long`

A category might have no budget set. Three ways to say that:

| Option | Problem |
|---|---|
| `long limitCents` with `0` meaning "unset" | `0` is a legal-looking number. The compiler cannot stop a caller forgetting the convention. |
| `Long limitCents`, `null` meaning "unset" | Violates the project's no-`null` rule, and `spentCents * 100 >= limitCents * 80` throws `NullPointerException` on auto-unboxing — at runtime, with no warning. |
| `OptionalLong` | Absence is in the type. The caller **cannot** read the value without first deciding what to do when it is missing. |

`OptionalLong` rather than `Optional<Long>` because it holds a primitive directly and
avoids boxing every limit into a `Long` object. For a handful of categories that is
irrelevant to performance — it is the honest type, which is the actual reason.

---

## Overflow note

`spentCents * 100` overflows a `long` above roughly 92 quadrillion cents. You will not
reach it. It is worth *noticing* that the concern exists, because the same
cross-multiplication trick in a system handling large integers does need the check.
