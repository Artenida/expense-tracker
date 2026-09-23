# Sprint 10 · Services over stores

**Time:** ~1.5 hours · **Prerequisites:** 09 · **Produces:** `ExpenseService`, `BudgetService`, `ExpenseNotFoundException`

---

## Purpose

The shortest sprint in the project, and the one most likely to make you ask "why does
this layer exist at all?" — because at the end of it, `ExpenseService.find` will be one
line that calls `ExpenseStore.find`.

That question is worth taking seriously rather than answering by reflex. The honest
answer is that **most of these methods are one line today, and three of them are not** —
and the three that are not are exactly the ones that would otherwise end up in a button
handler.

## What a service is for

Look at what each method does beyond delegating:

| Method | Beyond delegation |
|---|---|
| `find(filter)` | nothing |
| `findById(id)` | nothing |
| `add(...)` | constructs the `Expense` — so the UI never calls a domain constructor |
| `update(...)` | **turns `false` into `ExpenseNotFoundException`** |
| `delete(id)` | **turns `false` into `ExpenseNotFoundException`** |
| `topExpenses(month, n)` | translates a `YearMonth` into the two dates the store wants |

Three of six earn their keep. The other three exist so that the *whole* interface is in
one place — a UI that went to the store directly for reads and to the service for
writes would be harder to follow than one that always goes to the same place, and it
would make sprint 15's "no service call on the FX thread" rule ambiguous about reads.

The rule to carry forward: **a layer that is a pure pass-through today but is the
natural home for policy tomorrow is worth keeping.** A layer that will never hold
anything is not.

## New concepts

### 1. Policy versus mechanism

Sprint 08 made `store.delete` return `boolean` rather than throwing. Here is where that
pays off:

```java
public void delete(String id) {
    if (!store.delete(id)) {
        throw new ExpenseNotFoundException(id);
    }
}
```

The store reported a **fact**: no row had that id. The service applies a **policy**:
for this application, deleting something that is not there is an error worth telling the
user about.

The specification asks for exactly this — *"a row deleted by another process reports a
readable message rather than silently succeeding"*. Two people have the app open, one
deletes an expense, the other clicks delete on the same row. The store says "0 rows";
the service says "that expense no longer exists"; sprint 18 shows it in an `Alert`.

### 2. Dependency injection, without a framework

```java
public final class ExpenseService {
    private final ExpenseStore store;

    public ExpenseService(ExpenseStore store) {
        this.store = Objects.requireNonNull(store);
    }
}
```

The service is handed its store. It does not create one, and it does not know which
implementation it has — the field's type is the **interface**.

That is all dependency injection is. Spring and Guice automate the wiring for a few
hundred classes; with a dozen, `App` doing it by hand in sprint 13 is clearer and has no
magic in it. Understanding the pattern in this form first is why the specification
insists on no framework.

The consequence to notice: `new ExpenseService(someTestStore)` works, with no database
at all, because the constructor takes an interface.

### 3. An exception that carries data

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

Unchecked, like `StoreException` and `ValidationException`, for the same reason — so
nothing between the thrower and sprint 18's catch needs a `throws` clause.

Carrying the `id` as a field, not only inside the message, means a caller can *do*
something with it — refresh that row, log it structurally — rather than parsing it back
out of a string.

### 4. Where validation lives

It does not live here. `ExpenseService.add` constructs an `Expense`, and sprint 04's
constructor validates. If the amount is negative, the constructor throws
`ValidationException` before the store is ever reached.

This is the specification's R-4 — *"validation happens in the domain and service layers,
not in the SQL layer"* — and the reason there is no `if (amount <= 0)` anywhere in this
sprint. Putting one here would be a second copy of a rule that already has a home.

## What you build

- `ExpenseNotFoundException`
- `ExpenseService` — six methods
- `BudgetService` — three methods

## Definition of done

- [ ] `delete` on an unknown id throws `ExpenseNotFoundException` naming the id
- [ ] `update` does too
- [ ] `add` with an invalid amount throws `ValidationException` and inserts nothing
- [ ] Neither service imports anything from `java.sql`
- [ ] Both are constructible with a hand-written fake store, with no database

---

## Reading check

1. `ExpenseService` holds an `ExpenseStore`, not a `JdbcExpenseStore`. Name two things
   that become possible because of that.
2. `add` throws `ValidationException` from a constructor it called. Nothing in
   `ExpenseService` mentions that exception. How does it reach the caller, and what
   would be different if `ValidationException` were checked?
3. `find` is a one-line pass-through. Argue for deleting it, then argue against, then
   say which you would do in a codebase that already had fifty services.
4. Why does `topExpenses` take a `YearMonth` when the store takes two `LocalDate`s?
