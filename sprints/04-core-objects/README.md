# Sprint 04 · The core objects

**Time:** ~2 hours · **Prerequisites:** 03 · **Produces:** `Expense`, `Budget`, and the two architecture guard tests

---

## Purpose

`Expense` is the type the entire application exists to move around. It gets read from
the database, handed up through a service, wrapped for a table, edited in a dialog and
written back. Every layer touches it — which is exactly why it must know nothing about
any of them.

This sprint is about **making invalid states unrepresentable**. When you finish, there
will be no way to hold an `Expense` with a negative amount, a blank description or a
date next week. Not "you should not", not "we validate on save" — no way. Every method
downstream gets to assume its input is sane, and none of them need a defensive check.

## New concepts

### 1. Immutability

Every field is `final`. There are no setters. Editing produces a *new* object:

```java
Expense edited = original.edit(newAmount, newCategory, newDescription, newDate);
```

This costs an allocation and buys three things:

- **Thread safety for free.** From sprint 15 an `Expense` created on a background thread
  is read on the UI thread. An immutable object needs no locking, because there is no
  moment when it is half-updated.
- **Validation happens once.** A mutable object with setters must re-validate on every
  `setAmount`, and a caller can always forget. A constructor that validates is a gate
  everything must pass through.
- **No spooky action at a distance.** Handing an `Expense` to another class cannot come
  back to bite you, because that class cannot change it.

### 2. Static factories instead of public constructors

```java
private Expense(String id, BigDecimal amount, ..., Instant createdAt)  // validates

public static Expense create(BigDecimal amount, Category c, String d, LocalDate date)
public static Expense restore(String id, BigDecimal amount, ..., Instant createdAt)
```

Same fields, two entry points, and the names say which situation you are in:

- `create` is for a **new** expense. It generates the id and stamps `createdAt`.
- `restore` is for one that **already exists** — read back from the database in sprint
  07, where the id and timestamp are given, not invented.

A single public constructor could not express that difference. It would either force
the caller to invent an id (and the store would have to pass one it already has, which
reads as nonsense) or invent one internally (and the store could not preserve the real
one). Two named factories make both cases obvious at the call site.

### 3. `equals` and `hashCode`, and the contract between them

The rule the language relies on:

> If `a.equals(b)`, then `a.hashCode() == b.hashCode()`.

Break it and `HashMap`, `HashSet` and `distinct()` misbehave in ways that are very hard
to trace — an object goes into a set and cannot be found again.

This project breaks it *by accident* unless you are careful, because of sprint 03's
lesson. If `equals` compares amounts with `compareTo`:

```java
new BigDecimal("24.9").compareTo(new BigDecimal("24.90")) == 0    // equal
new BigDecimal("24.9").hashCode() != new BigDecimal("24.90").hashCode()
```

Two objects that `equals` says are equal, with different hash codes. The fix is to hash
a **scale-normalised** form — reuse `Money.toCents(amount)`, which collapses `24.9` and
`24.90` to the same `2490`.

This is the single most instructive detail in the sprint. It is also the kind of bug
that does not show up until sprint 11, when the SQL and stream summaries disagree for
no visible reason.

### 4. Guard tests

Two plain JUnit tests that walk `src/main/java` and fail if a forbidden `import` appears
in the wrong package. They enforce the whole architecture and take ten minutes to write.

You are installing them now, before there is much code, so that every subsequent sprint
tells you immediately when a layer leaks. Added at the end of the project they would
just produce a long list of violations you no longer remember making.

## What you build

- `Expense` — six fields, two factories, an `edit` method, `equals`/`hashCode`
- `Budget` — three fields, one factory
- `ArchitectureTest` — two guard tests

## Definition of done

- [ ] `Expense.create` with a blank description throws `ValidationException`
- [ ] `Expense.create` with tomorrow's date throws `ValidationException`
- [ ] Two expenses differing only in amount *scale* are equal **and** hash equal
- [ ] `edit` keeps the id and `createdAt`, and re-validates
- [ ] Both guard tests pass
- [ ] `domain` imports nothing from `java.sql`, `javafx`, `store`, `service` or `ui`

---

## Reading check

1. `edit` returns a new `Expense` and the old one still exists. What happens to the old
   one, and why is that not a leak?
2. Why does `restore` skip the "date is not in the future" check — or should it not?
   Argue both sides, then decide and write your decision in a comment.
3. You add a `notes` field to `Expense` and update `equals` but forget `hashCode`.
   Describe a concrete sequence of operations that produces wrong behaviour.
4. The guard test greps for the literal text `import java.sql`. Name a way to use a
   `java.sql` type that the test would not catch.
