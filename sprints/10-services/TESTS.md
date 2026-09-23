# Sprint 10 · Tests

`src/test/java/com/expensetracker/service/ExpenseServiceTest.java`, `BudgetServiceTest.java`

---

## Test without a database

The services take **interfaces**. So for the first time you can test the logic with no
file, no migration and no JDBC:

```java
final class FakeExpenseStore implements ExpenseStore {

    private final Map<String, Expense> rows = new LinkedHashMap<>();

    @Override public void add(Expense e) {
        if (rows.putIfAbsent(e.id(), e) != null)
            throw new StoreException("duplicate id " + e.id());
    }

    @Override public Optional<Expense> findById(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override public boolean update(Expense e) {
        return rows.replace(e.id(), e) != null;
    }

    @Override public boolean delete(String id) {
        return rows.remove(id) != null;
    }

    // ... find, addAll, totalsByCategory, findTop
}
```

Forty lines, no dependency, and tests that run in microseconds. This is the practical
payoff of sprint 07's interface — not the hypothetical Postgres swap, but the ability to
test the layer above without standing up the layer below.

**Write the fake yourself rather than reaching for Mockito.** You will read `LinkedHashMap`
faster than you will read `when(store.delete(any())).thenReturn(false)`, and for a store
with this few methods a real (if simple) implementation is less work than stubbing.

### Both kinds of test have a place

| Kind | Speed | Catches |
|---|---|---|
| Against `FakeExpenseStore` | microseconds | Service logic, error translation |
| Against `JdbcExpenseStore` + `@TempDir` | milliseconds | SQL, mapping, constraints, transactions |

Sprints 07–09 did the second kind. Do the first kind here, and add **one** integration
test at the end that wires a real store to a real service, to prove the pieces fit.

## ExpenseService

| Test | Proves |
|---|---|
| `addCreatesAndStores` | The returned expense has a non-blank id and is findable |
| `addReturnsTheCreatedExpense` | The id is available without a second lookup |
| `addRejectsAnInvalidAmount` | `ValidationException`, **and the fake store is still empty** |
| `addRejectsAFutureDate` | Same |
| `findDelegatesToTheStore` | The filter arrives unchanged |
| `findByIdReturnsEmptyForUnknown` | `Optional.empty()` |

`addRejectsAnInvalidAmount` must assert **both** halves:

```java
@Test
void addRejectsAnInvalidAmountAndStoresNothing() {
    assertThrows(ValidationException.class,
        () -> service.add(new BigDecimal("-5.00"), Category.OTHER, "bad", LocalDate.now()));

    assertTrue(fake.isEmpty());      // the half that matters
}
```

The throw alone would also happen in an implementation that stored first and validated
after.

## Error translation — the point of the sprint

```java
@Test
void deletingAnUnknownExpenseThrowsWithTheId() {
    ExpenseNotFoundException thrown = assertThrows(ExpenseNotFoundException.class,
        () -> service.delete("does-not-exist"));

    assertEquals("does-not-exist", thrown.id());
    assertTrue(thrown.getMessage().contains("does-not-exist"));
}
```

Assert on `id()` as well as the message. The field is what sprint 18 uses; the message
is what the log gets. Both are part of the contract.

| Test | Proves |
|---|---|
| `deleteSucceedsForAnExistingExpense` | No throw, row gone |
| `deletingAnUnknownExpenseThrowsWithTheId` | Policy applied |
| `updatingAnUnknownExpenseThrows` | Same, for update |
| `updateKeepsTheIdAndCreatedAt` | Via `expense.edit(...)` — end to end through the service |

### The concurrent-delete scenario

Worth writing explicitly, because it is the requirement in the specification rather
than an abstract edge case:

```java
@Test
void deletingARowAnotherProcessAlreadyRemovedIsReported() {
    Expense e = service.add(new BigDecimal("10.00"), Category.OTHER, "lunch", LocalDate.now());

    fake.removeDirectly(e.id());      // simulate the other process

    assertThrows(ExpenseNotFoundException.class, () -> service.delete(e.id()));
}
```

## BudgetService

| Test | Proves |
|---|---|
| `setLimitStoresTheBudget` | Findable afterwards |
| `setLimitTwiceKeepsOneBudget` | Spec test 7, at the service level |
| `setLimitRejectsZero` | `ValidationException` from `Budget.of`; nothing stored |
| `setLimitRejectsNegative` | Same |
| `findByCategoryIsEmptyWhenUnset` | The `NO_BUDGET` path sprint 11 relies on |
| `findAllIsEmptyNotNull` | A fresh application has no budgets, and that is not an error |

## The one integration test

```java
@Test
void serviceAndJdbcStoreWorkTogether() {
    Database db = new Database(tempDir.resolve("integration.db"));
    new SchemaMigrator(db).migrate();
    ExpenseService service = new ExpenseService(new JdbcExpenseStore(db));

    Expense added = service.add(new BigDecimal("24.90"), Category.GROCERIES,
                                "shop", LocalDate.of(2026, 9, 15));

    assertEquals(1, service.find(ExpenseFilter.of(YearMonth.of(2026, 9))).size());
    service.delete(added.id());
    assertTrue(service.find(ExpenseFilter.of(YearMonth.of(2026, 9))).isEmpty());
}
```

One test, covering the wiring. Everything else in this sprint runs against the fake.

A suite that is *all* integration tests is slow and tells you little about where a
failure is; a suite that is *all* unit tests against fakes can be entirely green while
the application does not work, because the fake and the real store disagree. The mix is
the point, and the ratio here — many fast, one real — is a reasonable default.
