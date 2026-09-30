# Sprint 12 · Reading check answers

Answers to the four questions at the end of `README.md`.

---

## 1. `line.split(",")` on `2026-09-15,GROCERIES,"Weekly shop, incl. wine",24.90`: how many pieces, and which field is wrong?

### Five pieces

```
[0] 2026-09-15
[1] GROCERIES
[2] "Weekly shop
[3]  incl. wine"
[4] 24.90
```

`split` does not know about quotes. It splits at every comma, including the one inside the
quoted description. That comma was the whole reason the writer added the quotes in the first
place.

### Which field is wrong

The **description**. It is split into two pieces, and both still carry a stray quote mark. A
reader that took the fields by position would also fail on the amount: index 3 is
` incl. wine"`, and the real amount has moved to index 4.

The failure is only loud here because there is one extra field. Suppose instead that a
description containing a comma were combined with a row that is also missing a field, such as
an empty amount. You would get the expected count of four, with the wrong values in each
position. That is the point the README makes: `split` does not throw, it quietly produces
data that looks plausible and is wrong.

`CsvReader.parseFields` returns four fields for this line. `aRecordWithFiveFieldsIsRejected` is
the counterpart: an unquoted comma in the description **is** five fields, and the reader says
so with the line number.

## 2. `readLine()` returns `null` at EOF. What does it return for a blank line in the middle of a file, and how does your reader tell them apart?

### An empty string

`readLine()` returns the line's contents without the terminator. An empty line in the middle of
the file comes back as `""`, a real `String` of length zero. A line containing only spaces comes
back as those spaces. Only when there is nothing left to read does it return `null`.

### Telling them apart

The loop checks for the two cases in different places:

```java
while ((line = reader.readLine()) != null) {   // null: end of file, the loop stops
    n++;
    if (line.isBlank()) {                      // "" or "   ": skipped, but still counted
        continue;
    }
    parsed.add(toExpense(n, line));
}
```

- `null` ends the loop in the `while` condition.
- `""` gets into the loop body, where `isBlank()` skips it. `isBlank` is used rather than
  `isEmpty` so that a line containing only spaces is skipped too.

One detail is easy to get wrong. `n++` runs **before** the blank-line check, so a skipped line
still counts towards the line number. If the counter were incremented only for parsed rows, every
error below a blank line would point one line too early. `blankLinesStillCountTowardsTheLineNumber`
guards against that.

The most common blank line is the last one. A file ending in `\n` gives no extra line at all,
because `readLine` treats the final terminator as ending the last line. A file ending in `\n\n`
gives one `""` before `null`. `blankLinesAreSkipped` covers blank lines in the middle of the file
and a trailing newline.

## 3. Parsing happens before the transaction opens. Name a failure mode that ordering prevents, and one it does not.

### Prevented: a bad line anywhere in the file

A malformed amount on line 5 throws `CsvFormatException` from `reader.read(path)`, **before**
`store.addAll` is called. No connection is taken, no transaction starts, and no row is sent. Spec
test 6 (`aMalformedFifthLineLeavesTheDatabaseExactlyAsItWas`) passes without depending on
rollback at all.

Other things follow from the same ordering:

- The user learns about line 800 in milliseconds, instead of after 799 inserts have been done and
  then undone.
- The transaction holds SQLite's write lock only for the insert itself. From sprint 15 a
  background import runs while the window may be writing too. A short transaction is a short
  window for `SQLITE_BUSY`.
- The rule that is easiest to reason about, "nothing has been written yet", holds at the moment
  the error happens. You do not have to trust the rollback.

### Not prevented: failures that only the database can detect

Parsing checks the **file** against the **domain rules**. It cannot check the file against the
**database**:

- **A constraint violation during the insert.** Examples are a duplicate id, a `CHECK` the domain
  does not mirror, or a disk that fills up halfway. The rows are valid until SQLite rejects one.
  `aDuplicateIdMidImportRollsBack` sets this up with a wrapper store, and sprint 09's rollback
  handles it.
- **Cancelling partway through.** Every row parsed fine, and the user pressed Cancel.
  `aCancelledImportAddsNothing` shows the rollback on cancel.
- **The file changing after it was read.** The parse and the insert see the same `List`, so the
  file changing on disk cannot cause a half-import. But what gets imported is the file as it was
  when it was read.
- **Importing the same file twice.** Each import is valid on its own, so the order of parsing
  and inserting does nothing to stop duplicates. That is question 4.

So the order of steps is the first protection, and sprint 09's transaction is the second. Each
covers failures the other cannot.

## 4. Export omits the `id`. Describe exactly what a user sees if they export September and immediately re-import the same file.

### Every September expense, twice

Say September has three expenses, 144.80 in total. After export and re-import:

- **The table** shows six rows. Each original has an identical twin: same date, category,
  description and amount. They sit next to each other, because they sort the same way (newest
  first, and same-day rows in a stable order).
- **The import dialog** reports success: "3 expenses imported". Nothing failed, because nothing
  was invalid.
- **The summary panel** shows 289.60 spent over 6 entries. Every category total doubles. The
  average stays the same, because both the total and the count doubled. A budget that was at 60%
  is now at 120% and shows `EXCEEDED`. That is the most visible symptom, and the one most likely
  to make someone think the app has a bug.
- **Deleting** one of a pair removes only that one, because each twin has its own id.

No error appears, because the file is valid and each row is new. The app cannot tell these
duplicates apart from someone who really did buy the same thing twice on the same day.

### Why that is the chosen behaviour

Import means "add these expenses". Its main use is bringing in expenses from somewhere else, such
as a bank export or another copy of the app. Those rows have no ids, so a new id for each row is
the only thing that can happen. Round-tripping your own export is the unusual case, and the
README says so. `exportingThenReimportingDuplicatesEveryExpense` makes the behaviour a test, so
that nobody later "fixes" it by accident.

The alternative is to export the id and make import an upsert. That would make round-tripping
harmless, but it has costs. An id column in files that users edit by hand becomes something they
can break: a copied row with the same id would silently **overwrite** another expense instead of
being added. And a file from anywhere else would need a separate path with no ids. That is a
reasonable design, but it is a different feature, "restore a backup", and it could be added later
as its own action.
