# Sprint 07 · Tests

`src/test/java/com/expensetracker/store/JdbcExpenseStoreTest.java`

Covers **spec tests 4 and 5** — the two that actually matter in this layer.

---

## Round trip

| Test | Proves |
|---|---|
| `addedExpenseIsFoundById` | The basic path works in both directions |
| `everyFieldSurvivesTheRoundTrip` | id, amount, category, description, date and `createdAt` all come back equal |
| `missingIdReturnsEmpty` | `findById("nope").isEmpty()` — not `null`, not a throw |
| `duplicateIdThrows` | Adding the same expense twice throws `StoreException` (the `PRIMARY KEY` fires) |

The second test is the one that earns its place. Each field exercises a different
conversion, and a bug in any one of them is invisible until you check that specific
field:

```java
@Test
void everyFieldSurvivesTheRoundTrip() {
    Expense original = Expense.create(new BigDecimal("24.90"), Category.GROCERIES,
                                      "weekly shop", LocalDate.of(2026, 9, 15));
    store.add(original);

    Expense loaded = store.findById(original.id()).orElseThrow();

    assertEquals(original.id(), loaded.id());
    assertEquals(0, original.amount().compareTo(loaded.amount()));   // compareTo!
    assertEquals(original.category(), loaded.category());
    assertEquals(original.description(), loaded.description());
    assertEquals(original.date(), loaded.date());
    assertEquals(original.createdAt(), loaded.createdAt());
    assertEquals(original, loaded);                                   // and as a whole
}
```

`compareTo` on the amount, for sprint 03's reason. The final `assertEquals(original,
loaded)` works because sprint 04 made `Expense.equals` scale-insensitive — if you had
used `BigDecimal.equals` there, this line would fail and the six above it would pass,
which is a confusing place to start debugging.

## Spec test 5 — a new session

```java
@Test
void dataWrittenInOneSessionIsPresentInANewSession() {
    Path file = tempDir.resolve("persist.db");

    Database first = new Database(file);
    new SchemaMigrator(first).migrate();
    Expense saved = Expense.create(new BigDecimal("10.00"), Category.OTHER,
                                   "lunch", LocalDate.of(2026, 9, 1));
    new JdbcExpenseStore(first).add(saved);

    // a completely separate Database, Connection and store over the same file
    Database second = new Database(file);
    assertTrue(new JdbcExpenseStore(second).findById(saved.id()).isPresent());
}
```

Note the second `Database` does **not** run the migrator — the schema is already in the
file. That is the test quietly proving the file, not the object, is what holds the data.

## Spec test 4 — injection

```java
@Test
void aDescriptionThatLooksLikeSqlIsStoredAsText() {
    String attack = "'; DROP TABLE expenses; --";

    Expense e = Expense.create(new BigDecimal("1.00"), Category.OTHER,
                               attack, LocalDate.of(2026, 9, 1));
    store.add(e);

    assertEquals(attack, store.findById(e.id()).orElseThrow().description());

    // the table still exists and still works
    Expense after = Expense.create(new BigDecimal("2.00"), Category.OTHER,
                                   "still here", LocalDate.of(2026, 9, 2));
    store.add(after);
    assertTrue(store.findById(after.id()).isPresent());
}
```

Both halves matter. The first proves the value round-tripped **unchanged** — no escaping,
no mangling, the string you stored is the string you get. The second proves the table
survived. A test that only checked the second would also pass if `add` had silently
failed.

### Watch it fail

Worth ten minutes. Temporarily add a concatenating method:

```java
// DO NOT KEEP THIS
void addUnsafe(Expense e) {
    String sql = "INSERT INTO expenses VALUES ('" + e.id() + "', "
               + Money.toCents(e.amount()) + ", '" + e.category().name() + "', '"
               + e.description() + "', '" + e.date() + "', '" + e.createdAt() + "')";
    ...
}
```

Point the test at it. You will get a syntax error rather than a dropped table — SQLite's
JDBC driver only executes the first statement, so the `DROP` never runs. That is worth
seeing, because it shows the protection you are relying on by *accident* is thinner than
the one you get on purpose. Change the description to `x' , 0, 'X', 'pwned', '2020-01-01
', '2020-01-01T00:00:00Z') --` and the row goes in with attacker-chosen values and no
error at all.

Delete `addUnsafe` afterwards.

## Mapper edge cases

| Test | Proves |
|---|---|
| `unknownCategoryInTheDatabaseIsReported` | Insert a raw row with `category = 'FOOD'` via a plain `Statement`, then `findById`. Expect a throw whose message lists the valid categories. |
| `amountIsReadAsCentsNotDouble` | Insert `amount_cents = 2490` raw; assert the amount compares equal to `24.90` |
| `largeAmountsSurvive` | `999_999_999` cents round-trips — proves `getLong`, not `getInt` |

The first one is the test that documents "someone edited the database by hand". Write it
with a plain `Statement` and a literal so it is obvious the bad data came from outside
the application.

## What is not tested here

No test asserts that `add` uses a `PreparedStatement`. You cannot check that from the
outside — the injection test above is the closest you can get, and as the "watch it
fail" exercise shows, it is not airtight either. This is a case where the test documents
the intent and **code review enforces the rule**. Worth knowing which of your guarantees
come from tests and which come from discipline.
