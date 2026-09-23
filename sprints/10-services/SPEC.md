# Sprint 10 · Build specification

Both services in `src/main/java/com/expensetracker/service/`.

---

## `service/ExpenseNotFoundException.java`

```java
public class ExpenseNotFoundException extends RuntimeException {

    private final String id;

    public ExpenseNotFoundException(String id) {
        super("no expense with id " + id);
        this.id = id;
    }

    public String id() { return id; }
}
```

---

## `service/ExpenseService.java`

```java
public final class ExpenseService {

    private final ExpenseStore store;

    public ExpenseService(ExpenseStore store) { ... }

    public List<Expense>     find(ExpenseFilter filter);
    public Optional<Expense> findById(String id);
    public Expense           add(BigDecimal amount, Category category,
                                 String description, LocalDate date);
    public Expense           update(Expense edited);
    public void              delete(String id);
    public List<Expense>     topExpenses(YearMonth month, int limit);
}
```

### `add`

```java
public Expense add(BigDecimal amount, Category category, String description, LocalDate date) {
    Expense expense = Expense.create(amount, category, description, date);
    store.add(expense);
    return expense;
}
```

Takes the **parts**, not an assembled `Expense`. That keeps `Expense.create` — and the
id generation and the `createdAt` stamp — out of sprint 17's dialog. The dialog collects
four values from four controls and hands them over; it never constructs a domain object.

Returns the created `Expense` so the caller has the generated id without a second
lookup.

If validation fails, `Expense.create` throws and `store.add` is never reached. Nothing
is written, which is the specification's *"invalid input is rejected and nothing is
inserted"* — achieved by ordering, not by a check.

### `update`

```java
public Expense update(Expense edited) {
    if (!store.update(edited)) {
        throw new ExpenseNotFoundException(edited.id());
    }
    return edited;
}
```

Takes a whole `Expense` here, unlike `add`, because the caller already has one — sprint
17 gets it from the selected table row and calls `expense.edit(...)` on it. The id and
`createdAt` come along, which is exactly what `edit` preserves.

### `delete` and `topExpenses`

```java
public void delete(String id) {
    if (!store.delete(id)) throw new ExpenseNotFoundException(id);
}

public List<Expense> topExpenses(YearMonth month, int limit) {
    ExpenseFilter filter = ExpenseFilter.of(month);
    return store.findTop(filter.from(), filter.to(), limit);
}
```

`topExpenses` reuses `ExpenseFilter` purely for its date arithmetic. The alternative —
`month.atDay(1)` and `month.atEndOfMonth()` written out here — would be a second place
that knows how to turn a month into a range, and the first one to be forgotten when
something changes.

---

## `service/BudgetService.java`

```java
public final class BudgetService {

    private final BudgetStore store;

    public BudgetService(BudgetStore store) { ... }

    public List<Budget>       findAll();
    public Optional<Budget>   findByCategory(Category category);
    public Budget             setLimit(Category category, BigDecimal monthlyLimit);
}
```

```java
public Budget setLimit(Category category, BigDecimal monthlyLimit) {
    Budget budget = Budget.of(category, monthlyLimit);   // validates, stamps updatedAt
    store.upsert(budget);
    return budget;
}
```

One method named for what the user does, over a store method named for what the database
does. `setLimit` reads better at the call site in sprint 18 than `upsert` would, and it
is the sort of small naming decision that decides whether the UI code is readable.

There is no `deleteBudget`. The original specification never asks for one, and a method
nothing calls is a method that will be wrong when something finally does call it.

---

## A note on what is deliberately absent

No caching. No `Map<String, Expense>` kept between calls, no "only re-query if the month
changed".

It would work today and break the moment a second thing writes to the database — and
from sprint 15 there *is* a second thing, because a background import runs while the
table holds rows it read earlier. The specification's U-5 is the same rule from the
other end: *"do not try to patch the `ObservableList` by hand after an insert — you will
drift out of sync with the database."*

The services are stateless. Every call goes to the store. That is a deliberate
simplification with a real cost (more queries) and a real benefit (the displayed data
cannot disagree with the file), and on a local SQLite file the cost is not measurable.
