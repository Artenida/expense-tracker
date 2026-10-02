# Sprint 18 · Reading check answers

Answers to the four questions at the end of `README.md`.

---

## 1. The `switch` has a `default` even though every exception in the project is covered. Why does the compiler insist, and what happens at runtime without it?

### Why the compiler insists

A `switch` on patterns, used as an **expression**, must be **exhaustive**: every possible
value of the selector type has to match some case. The selector here is `Throwable`, and
the cases cover four subclasses. The compiler has no way to know that these are the only
exceptions that will ever arrive. `Throwable` is open to extension, and anything can subclass
it: `IllegalArgumentException` from `Money.parse`, an `OutOfMemoryError`, a
`NullPointerException` from a bug, a driver's own exception. "Every exception in the
project" is a fact about today's code, not something the type system knows.

### What happens without it

It does not get as far as runtime. I compiled a `switch` over `Throwable` with no
`default`:

```
error: the switch expression does not cover all possible input values
```

So the README's line, that "an uncovered one at runtime would throw `MatchException`", does
not apply to this switch. `MatchException` comes from a different situation, which I also
reproduced:

1. A switch over a **sealed** type that covers every permitted subtype compiles **without**
   a `default`, because the compiler can prove it is exhaustive.
2. Later, the sealed type gains a new subtype and is recompiled, **but the switch is not**.
3. At runtime the new subtype reaches the old switch, and it throws
   `java.lang.MatchException`.

That is what "an error report turned into a second error" really means. It cannot happen
here, because `Throwable` is not sealed. The `default` is required to compile, and it also
does a real job: an unexpected `IllegalStateException` still gets the "Something went
wrong / See the log for details" dialog, and its trace is still logged.

One more case. A `null` selector throws `NullPointerException` before any case is tried,
`default` included, unless there is an explicit `case null`. Nothing in this project
passes `null`. `Task.getException()` is non-null in `setOnFailed`.

## 2. `setOnEditCommit` does not fire when the user presses Escape. Why is that the right behaviour for a cell that writes to a database?

Because a write to the database should only happen when the user **decides** it, and Escape
is the universal way of saying "I didn't mean that".

- **Escape means abandon.** Everywhere on a desktop, Escape cancels: closing a dialog,
  dismissing a menu, leaving an edit. If Escape saved, the one key users press to back out
  of a mistake would make it permanent.
- **Editing in progress isn't intent.** While the cell is open the user may be partway
  through, with `45` typed on the way to `450.00`. Saving on anything other than an explicit
  Enter risks writing a value that was never finished. That is a real budget, and the
  summary's colours change as a result.
- **There is nothing to undo.** The budgets table has no history. `setLimit` upserts, and the
  previous limit is gone. An accidental save cannot be reversed, so the threshold for saving
  should be high.
- **The display and the database stay in step.** On cancel, the cell goes back to its stored
  value, and nothing was sent, so there is nothing to reconcile. Every path that does commit
  but does not save, whether empty, invalid or failed, calls `reload()`, so the cell never
  shows a value that is not in the database.

Clicking away behaves the same, so in this JavaFX version (21) no event fires there either.
Some applications save on focus loss, and that is a legitimate choice for a form with a Save
button behind it. For a cell that writes straight to disk, "Enter or nothing" is the safer
contract.

## 3. Sorting by a `String` amount column puts `100.00` before `24.90`. Why do ISO date strings not have the same problem?

String comparison goes character by character from the left, and the first difference
decides. That matches numeric order only when **every value has the same length and the
most significant part comes first**.

**Amounts break both conditions.** `100.00` and `24.90` have different lengths, so their
digits are not aligned: the `1` in `100.00` is in the hundreds place, and the `2` in `24.90`
is in the tens place. The comparison sees `'1' < '2'` at the first position and stops. It is
comparing a hundreds digit with a tens digit.

**ISO dates meet both conditions:**

- **Fixed width.** `yyyy-MM-dd` is always 10 characters. Months and days are zero-padded:
  `2026-09-05`, not `2026-9-5`.
- **Most significant first.** Year, then month, then day. The first character that differs
  is in the largest unit that differs.

So comparing the text is comparing the dates. `2026-09-30 < 2026-10-01` is decided at the
6th character (`0 < 1`), which is the month.

It is still luck rather than design, as the README says, and the luck has edges:

- Years before 1000 or after 9999 break the fixed width. ISO allows `+10000-01-01`.
- A date format with day first (`05/09/2026`) or without padding (`2026-9-5`) sorts wrongly
  straight away.
- A date shown in the user's locale (`5 Sep 2026`) would sort alphabetically by day number.

The date column is still a `String` here, and that is fine while it is ISO. The lesson from
the amount fix applies to any column: **store the value, format in the cell**. The amount
column now holds a `BigDecimal` and sorts numerically. `ExpenseRowTest.amountValuesSortNumerically`
checks `9.00 < 24.90 < 100.00`.

## 4. `ErrorDialogs` logs the trace and shows a sentence. What is lost if you show the trace, and what is lost if you do not log it?

### Showing the trace: the user loses the message

- **They cannot act on it.** `org.sqlite.SQLiteException: [SQLITE_READONLY] attempt to write
  a readonly database at org.sqlite.core.DB.newSQLException(...)`, plus forty more lines,
  does contain the answer, but buried in text written for a different reader. "The database
  file is read-only. Check the file permissions." tells them what to do.
- **It looks like a crash.** A wall of `at com.expensetracker...` says that the program
  broke, even when the cause is ordinary and fixable, such as a locked file or an expense
  deleted elsewhere. Users lose trust, or stop reading error dialogs altogether.
- **Internal details leak.** Store messages are written for developers: `failed to delete
  expense 3f2a...`, file paths, SQL. `ErrorDialogsTest.storeMessagesAreNotShownToTheUser`
  checks that the sentence does not repeat them.

### Not logging it: the developer loses the cause

- **The only record of what actually happened is gone.** "Could not reach the database"
  covers a dozen different failures. The trace, with SQLite's result code, the failing
  statement and the call path, is what tells them apart. Without it, a bug report reads
  "it said something went wrong", and nobody can reproduce it.
- **The `default` case becomes useless.** "Something went wrong. See the log for details."
  is a promise. If nothing was logged, the message points at an empty log, and an
  unexpected exception, the kind most worth investigating, leaves no trace at all.
- **Patterns are invisible.** One user hitting `SQLITE_BUSY` is noise. Fifty logged
  occurrences that all happen after an import show where the bug is.

`ErrorDialogs.show` logs **first**, then shows the dialog. So even if building or showing
the `Alert` failed, the cause would already be recorded. The two outputs are not
alternatives. They are for two different readers, and dropping either one means one of
those readers is left with nothing.
