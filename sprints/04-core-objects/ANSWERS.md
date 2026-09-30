# Sprint 04 · Reading check answers

Answers to the four questions at the end of `README.md`.

---

## 1. `edit` returns a new `Expense` and the old one still exists. What happens to the old one, and why is that not a leak?

**It stays exactly as it was until nothing refers to it. Then the garbage collector
frees it.**

After this:

```java
Expense original = Expense.create(new BigDecimal("24.90"), LEISURE, "coffee", today);
Expense fixed    = original.edit(new BigDecimal("4.90"), LEISURE, "coffee", today);
```

there are two objects in memory, and both are complete, valid expenses. `original` has
not been damaged or emptied - `editDoesNotMutateTheOriginal` checks exactly that. It is
simply a description of the expense *as it was*.

What happens next depends on who still holds it:

- **Typically, nobody.** Sprint 18's table will replace the old object in its list with
  the new one, and the local variable goes out of scope when the method returns. The
  old `Expense` is now **unreachable** - no chain of references from running code leads
  to it.
- The JVM's **garbage collector** periodically finds unreachable objects and reclaims
  their memory. You never free anything yourself in Java; you stop referring to it.

**Why that is not a leak.** A memory leak in Java is not "an object that exists". It is
*an object that is still reachable but will never be used again* - usually because it
is sitting in a list, map or cache that keeps growing. The collector cannot free it,
because as far as it can tell, somebody still wants it.

The old `Expense` is the opposite case: unreachable, so collectable. A short-lived
object like this is also the cheapest thing a modern JVM does. Most objects die young,
and the collector is built around that - reclaiming a young, dead object costs almost
nothing.

**When it *would* be a leak:** if some code appended every version to a list
(`history.add(edited)`) and never removed any. The leak would then be that list, not
`edit`. Immutability does not cause leaks; holding references does.

**And when keeping it is a feature:** because the old object cannot change, holding on
to it is safe. An undo button could keep `original` and put it back. With a mutable
object, the "old" version would have been overwritten in place and there would be
nothing to go back to.

## 2. Why does `restore` skip the "date is not in the future" check - or should it not?

In this codebase **`restore` does not skip it**. It calls the same private constructor
as `create`, so every rule runs. The question asks whether that is right, so here are
both sides.

### The case for skipping it in `restore`

- **"Future" depends on the clock of the machine reading, not the one writing.** An
  expense entered today at 23:30 in one time zone can be "tomorrow" relative to a
  machine whose clock or zone is behind. A laptop with a dead clock battery booting in
  2001 would find almost every row in the future.
- **The fact was true when it was checked.** `create` validated the date at the moment
  it was entered. `restore` is not accepting a new claim, only reading back an old one.
- **The failure is out of all proportion.** Sprint 07's store builds a list of every
  expense. One row that fails validation throws from the mapper and the whole list fails
  to load - the user loses access to every expense because of one date.

### The case for keeping it

- **"If you hold an `Expense`, it is valid" is the whole design.** An exception for
  `restore` means the promise becomes "valid, unless it came from the database" - which
  is where almost all of them come from.
- **The database is not a trusted source.** It is a file on disk. Someone can edit it by
  hand, an import bug can write a bad row, a later version of the app can have different
  rules. The mapper is the earliest point to find out.
- **Failing loudly beats being quietly wrong.** A future-dated row that loads fine is a
  row that silently lands in next month's totals, triggers the wrong budget warning, and
  looks like a summary bug three sprints later. A clock set to 2001 is visible,
  obvious, and fixed by setting the clock.
- **Selective validation drifts.** Once `restore` skips one rule, the next person will
  skip another "because it was checked on the way in", and soon the two doors behave
  differently.

### Decision

**Keep the check in `restore`.** The Javadoc on `Expense.restore` records it:

```java
 * <p>Decision (reading check 2): {@code restore} runs the full validation,
 * including "not in the future". A stored row that fails it was edited by hand or
 * written by a bug, and the mapper is the place to find out - not three layers up
 * when a total is wrong. The cost is that a clock set backwards makes such a row
 * unloadable; that is loud and fixable, whereas a silently accepted bad row is not.
```

It is also what `SPEC.md` asks for. The real risk from the "skip" side - one bad row
locking the user out of everything - is worth remembering for sprint 07. If it comes
up, the answer is for the *store* to decide how to handle a row that will not restore
(skip it and report it, say), not to weaken `Expense` so that bad rows restore fine.

## 3. You add a `notes` field and update `equals` but forget `hashCode`. What goes wrong?

**Nothing breaks. This particular mistake is legal. The opposite mistake is the
dangerous one.**

The contract has one direction only:

> If `a.equals(b)`, then `a.hashCode() == b.hashCode()`.

Trace the change the question describes:

```java
// equals:   compares id, amount, ..., createdAt, notes
// hashCode: hashes   id, amount, ..., createdAt           (notes forgotten)
```

Take any two expenses that `equals` calls equal. They match on **every** field,
including `notes`. So they certainly match on the subset of fields `hashCode` uses, and
they get the same hash. The contract holds.

What you get instead is more **collisions**. Two expenses that differ *only* in `notes`
now land in the same hash bucket. A `HashSet` still keeps them apart correctly - it
calls `equals` on everything in the bucket - just slightly more slowly. A `hashCode` is
allowed to ignore fields; `return 0;` is a correct hash code, just a terrible one.

### The version that really does break

Now the reverse: `notes` added to `hashCode`, forgotten in `equals`. Equal objects can
now hash differently. The concrete sequence:

```java
Expense a = Expense.restore("id-1", ..., createdAt, "paid cash");
Expense b = Expense.restore("id-1", ..., createdAt, "paid by card");

a.equals(b)                          // true  - equals ignores notes
a.hashCode() == b.hashCode()         // false - hashCode includes notes

Set<Expense> seen = new HashSet<>();
seen.add(a);
seen.contains(b)                     // false, although a.equals(b)
seen.add(b);
seen.size()                          // 2, two "equal" elements in one set
```

`HashSet.contains(b)` hashes `b`, looks only in `b`'s bucket, finds nothing there -
`a` is in a different bucket - and returns false without calling `equals` at all.
Anything built on hashing goes wrong the same way: `distinct()` keeps duplicates,
`HashMap.get` returns null for a key that is there, and sprint 18 could fail to find the
row it is meant to update.

This is exactly the `BigDecimal` scale bug in disguise: `equals` using `compareTo`
(ignores scale) and `hashCode` using the raw `BigDecimal` (includes scale) is
"`hashCode` looks at something `equals` does not".

### The habit that prevents both

`equals` and `hashCode` should be written from the **same field list**, and changed
together, in one edit. `scaleDoesNotAffectEqualityOrHash` checks the `HashSet`
behaviour directly for the scale case. For a new field, you would add the same kind of
test: two objects that `equals` says are equal, then `assertEquals` on their hashes and
on the size of a set holding both.

Records, from sprint 05, remove the problem: the compiler generates both methods from
the one component list, so they cannot drift apart.

## 4. The guard test greps for the literal `import java.sql`. Name a way to use a `java.sql` type that it would not catch.

**A fully-qualified name.** Java does not require an import; it only saves typing:

```java
package com.expensetracker.service;

public class Sneaky {
    void run() throws java.sql.SQLException {
        java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:x.db");
    }
}
```

No line of this file contains `import java.sql`, so `onlyStoreImportsJavaSql` passes,
and the service now depends on JDBC just as much as if it had imported it.

Other ways around the same test:

| Way round | Why the text match misses it |
|---|---|
| `import static java.sql.Types.INTEGER;` | the text is `import static java.sql`, which does not contain `import java.sql` |
| `import  java.sql.Connection;` (two spaces) | the literal has one space |
| `var c = store.openConnection();` | uses a `Connection` without ever naming its type |
| `Class.forName("java.sql.DriverManager")` | reflection - a string, not a dependency the compiler knows about |
| `import javax.sql.DataSource;` | a different package, same layer violation |

And one false positive in the other direction: a comment such as
`// no import java.sql here` in a service file would *fail* the test.

**Why the test is still worth having.** The leak that actually happens is not someone
typing `java.sql.Connection` in full to dodge a test. It is an IDE auto-import adding
`import java.sql.Date;` when you meant `LocalDate`. The text match catches exactly
that, with a message naming the file.

A tool that inspects compiled bytecode, such as **ArchUnit**, closes every gap in the
table except reflection, because the compiled class records every type it really uses,
however it was written. For this project, a check that reads the import block the way
your eye does is the better teaching tool - and it was confirmed to fail, naming
`Expense.java`, when a real `import java.sql.Connection;` was added.
