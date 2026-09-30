# Sprint 10 · Reading check answers

Answers to the four questions at the end of `README.md`.

---

## 1. `ExpenseService` holds an `ExpenseStore`, not a `JdbcExpenseStore`. Name two things that become possible because of that.

### 1. Testing the service with no database

```java
ExpenseService service = new ExpenseService(new FakeExpenseStore());
```

`FakeExpenseStore` keeps rows in a `LinkedHashMap`. The service cannot tell the difference,
because it only ever calls methods declared on the interface. So the service's own logic - the
`ExpenseNotFoundException` translation, the order of "build then store", the month-to-dates
conversion - is tested in microseconds, with no file, no migration and no JDBC.

It also makes situations easy to set up that are awkward with a real database. The
specification's concurrent-delete scenario is one line: `fake.removeDirectly(id)` plays the part
of "another process deleted it". With SQLite that would need a second connection and careful
ordering.

If the field were `JdbcExpenseStore`, none of this compiles: the constructor would demand the
concrete JDBC class, and every service test would need a real database on disk.

### 2. Replacing the storage without touching the service

A Postgres implementation - the original specification's optional milestone - would be a new
class, `PostgresExpenseStore implements ExpenseStore`, and one changed line in sprint 13's `App`:

```java
ExpenseService expenses = new ExpenseService(new PostgresExpenseStore(dataSource));
```

`ExpenseService` does not change, is not recompiled for a reason, and cannot have accidentally
depended on anything SQLite-specific - the compiler only let it see the interface.

### A third, for good measure: wrapping

Because the service only knows the interface, something can be put *between* them without either
side knowing. A `LoggingExpenseStore` that implements `ExpenseStore`, logs each call and passes it
to the real store; a `SlowExpenseStore` that sleeps before each call, to test in sprint 15 that
the window stays responsive while the store is slow. This is the **decorator** pattern, and it
only works because the service asked for the interface.

## 2. `add` throws `ValidationException` from a constructor it called. Nothing in `ExpenseService` mentions that exception. How does it reach the caller, and what would be different if `ValidationException` were checked?

### How it reaches the caller

By **propagation**. An exception thrown and not caught leaves the method it was thrown in, and
then the method that called that one, and so on up the call stack, until something catches it.

```
ExpenseService.add(...)
  -> Expense.create(...)                   static factory
       -> new Expense(...)                 private constructor
            Validation reports errors
            throw new ValidationException(errors)    <- thrown here
       <- not caught: leaves the constructor
  <- not caught: leaves create
<- not caught: leaves ExpenseService.add - store.add is never reached
caller (sprint 17's dialog) <- catches it and paints the messages
```

Nothing in between has to mention it, because `ValidationException extends RuntimeException` - it
is **unchecked**, and the compiler does not track unchecked exceptions. The service's Javadoc
lists it with `@throws`, so a reader knows to expect it - but that is documentation, not a rule
the compiler enforces.

The same propagation is what makes "nothing is inserted" true. The throw leaves `add` at the
`Expense.create` line, so the `store.add(expense)` line after it never runs. No `if`, no
`try` - just the order of two statements.

### If `ValidationException` were checked

It would `extend Exception` instead, and the compiler would insist that every method it can escape
from either **catches** it or **declares** it with `throws`. The requirement spreads up the call
chain:

```java
// sprint 04
private Expense(...) throws ValidationException { ... }
public static Expense create(...) throws ValidationException { ... }
public static Expense restore(...) throws ValidationException { ... }
public Expense edit(...) throws ValidationException { ... }

// this sprint
public Expense add(...) throws ValidationException { ... }
public Expense update(...) { ... }        // does not create, so it would not need it - but...
```

And it does not stop there:

- **`JdbcExpenseStore.map` calls `Expense.restore`.** Every query - `findById`, `find`, `findTop` -
  would have to catch `ValidationException` or declare it. Declaring it puts it on the
  `ExpenseStore` **interface**, so `find` would read `throws ValidationException` - "looking up
  September might fail validation", which is nonsense to any caller. And the fake would have to
  declare it too.
- **Lambdas stop compiling.** `rows.stream().map(this::map)` inside a store, or
  `button.setOnAction(e -> service.add(...))` in the UI: `Function.apply` and
  `EventHandler.handle` declare no checked exceptions, so a lambda that can throw one is a compile
  error. Every such lambda would need a `try`/`catch` inside it - sprint 03's answer to its own
  question 4, now showing up in real code.
- **Tests change too.** Every test that builds an expense, even with valid data, would need
  `throws ValidationException` on the method or a `try` around the call.

What you would gain is that the compiler forces sprint 17's dialog to handle it. But the dialog
calls `Validation` *first* and only calls `add` with values that passed - so by the time `add`
throws, a check was skipped somewhere, which is a bug, not a situation to recover from. That is the
line sprint 03 drew: **checked for situations, unchecked for bugs.**

## 3. `find` is a one-line pass-through. Argue for deleting it, then argue against, then say which you would do in a codebase that already had fifty services.

### For deleting it

- **It does nothing.** `service.find(filter)` and `store.find(filter)` are the same call. A method
  whose body is its signature is code to read, name, document and test for no behaviour.
- **Every layer costs something.** Each extra hop is one more file to open when following a bug,
  one more place a parameter could be renamed or reordered. "Just in case" layers are how codebases
  end up with five classes between a button and a query.
- **YAGNI - "you aren't gonna need it".** If policy for reads ever appears, a method can be added
  then, with the policy in it. Writing the empty version now is guessing at a future that may not
  come.
- **The UI already has the interface.** `ExpenseStore` hides the database perfectly well. The UI
  depending on it would not be a layering violation.

### Against deleting it

- **One door.** With `find` deleted, the UI reads from the store and writes through the service.
  Every UI class then needs both, and every reader has to ask "why does this one go to the store
  directly?" The rule "the UI talks to services" is simple enough to check at a glance; "the UI
  talks to services, except for reads" is not.
- **Sprint 15's rule stays unambiguous.** *No service call on the JavaFX thread* is easy to state
  and to review. If reads went to the store, the rule would need a second clause, and the
  un-covered case - a store call on the UI thread - is exactly the one that freezes the window.
- **It is the natural home for read policy.** Read policy is not hypothetical here: "hide expenses
  in categories the user archived", "never show more than two years back", "log slow queries". Each
  would go in `ExpenseService.find`. Without it, each would go into the UI or into the store - the
  two places policy does not belong.
- **The UI would depend on the store package.** Today the UI can depend on `service` and `domain`
  only. Deleting `find` adds `store` to that list, and the next person needing a quick query will
  call the store for that too.
- **It costs almost nothing.** One line, and a test that runs in microseconds.

### What I would do in a codebase with fifty services

**Keep it.** With fifty services, **consistency is worth more than the saving of one line.**

In a small project you can hold every exception in your head. In one with fifty services, the
people working in it rely on conventions: "the UI calls services" is something a new developer
learns once and then trusts everywhere. A few services where the UI calls the store directly
"because the method would have been a pass-through" makes that convention false. From then on
every call site has to be checked individually, and each new developer has to rediscover which
services are the exceptions.

A pass-through method is the cheapest kind of code there is to own. An inconsistent rule is one
of the most expensive. With fifty services, one line per read method is a price worth paying to
keep "the UI always goes through the service" true without exceptions.

What I *would* delete is a whole service that is **nothing but** pass-throughs and has no plausible
policy - a layer that will never hold anything. `ExpenseService` is not that: four of its six
methods already do something.

## 4. Why does `topExpenses` take a `YearMonth` when the store takes two `LocalDate`s?

**Because each layer speaks in its own terms, and the service is where one turns into the other.**

### The caller thinks in months

The user picks *September 2026* in the window. The summary panel shows *the top 5 for September*.
`MonthSummary` has a `yearMonth` component; `ExpenseFilter` holds a `YearMonth`. Everything the UI
knows is "a month". Making it pass two dates would force it to compute them - and so to know that a
month's range is the 1st to the last day, inclusive, and how long February is.

### The store thinks in date ranges

`findTop(from, to, limit)` binds two dates into `BETWEEN ? AND ?`. It does not know what a month
is - it will just as happily take a week or a year. That generality is right for the SQL layer: the
store offers mechanism, not meaning.

### The service translates, once, by borrowing

```java
ExpenseFilter filter = ExpenseFilter.of(month);
return store.findTop(filter.from(), filter.to(), limit);
```

The service does not write `month.atDay(1)` and `month.atEndOfMonth()` itself. `ExpenseFilter`
already owns that arithmetic (sprint 05, question 4), so the service reuses it. There is exactly one
place in the project that knows how a month becomes a range. If the rule ever changed - say, a
"month" starting on payday rather than the 1st - it would change in one place, and `find`,
`topExpenses` and the summary would all follow.

### It rules out ranges that make no sense

Taking a `YearMonth`, the service cannot be asked for "the top 5 from 15 September to 3 August" or
from the 1st to the 29th of a 30-day month. The type only allows whole months, which is the only
thing the UI ever needs - sprint 04's idea of making invalid input impossible to express, applied
to a parameter.

### Tested without SQL

`topExpensesTurnsTheMonthIntoItsFirstAndLastDay` calls `topExpenses(YearMonth.of(2024, 2), 5)`
against the fake and checks the store received `2024-02-01` and `2024-02-29` - the leap day
included. That is the one thing this method adds, tested on its own.
