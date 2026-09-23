# Sprint 12 · CSV in and out

**Time:** ~2.5 hours · **Prerequisites:** 11 · **Produces:** `CsvReader`, `CsvWriter`, `CsvFormatException`, `ImportService`

---

## Purpose

The last sprint before the window. It brings in file I/O, and it is where "export can be
re-imported with no loss" turns out to be a much stronger requirement than it sounds.

It is also a good exercise in **writing a parser for a format you do not control**. CSV
looks trivial until a description contains a comma, and then a quote, and then a
newline. The naive `line.split(",")` handles none of those, and it fails *silently* —
producing plausible-looking wrong data rather than an error.

## The format, decided

The original specification never defines one, which makes "no loss" untestable. Pinned
down here:

```
date,category,description,amount
2026-09-15,GROCERIES,"Weekly shop, incl. wine",24.90
2026-09-16,TRANSPORT,bus fare,2.50
2026-09-17,LEISURE,"He said ""hello""",8.00
```

| Decision | Choice | Why |
|---|---|---|
| Header | Required, validated | A file with the columns in the wrong order is a data-corruption event, not a parse error. Checking the header catches it. |
| Quoting | RFC 4180 | Quote a field if it contains `,`, `"` or a newline. `""` escapes a quote. |
| Date | ISO `yyyy-MM-dd` | Same as the database. No locale ambiguity. |
| Amount | Plain decimal, `.` | Sprint 03's rule, applied to files as well as text fields. |
| Category | The **enum name** | `GROCERIES`, not `Groceries`. Stable across any future UI translation. |
| `id` | **Not exported** | See below. |
| Encoding | UTF-8, explicit | |

### Why no `id` column

Export writes what the user *spent*, not the application's internal bookkeeping. A
re-import therefore creates **new** expenses with new ids, rather than restoring the old
ones.

That is a real decision with a real consequence: importing a file you just exported
gives you every expense twice. It is the right default — import is "add these expenses",
not "restore this backup" — and it is the kind of thing a future reader will assume you
overlooked unless it is written down. Put it in the project README.

The alternative (export the id, make import an upsert) is defensible and more work.
Choose one, write it down, and make the tests match.

## New concepts

### 1. Readers, writers and charsets

```java
try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
    String line;
    while ((line = reader.readLine()) != null) { ... }
}
```

Two things to get right:

- **Always name the charset.** `Files.newBufferedReader(path)` uses UTF-8 by default
  since Java 18, but `new FileReader(path)` uses the *platform* default, which differs
  between machines. A file written on one and read on another mangles any non-ASCII
  description. Being explicit costs one argument and removes a whole category of bug.
- **`readLine()` returns `null` at end of file**, not an empty string. The
  `while ((line = reader.readLine()) != null)` idiom is assignment-inside-condition,
  which is unusual Java and worth recognising on sight — you will see it constantly.

`BufferedReader` wraps the underlying stream so reads come from memory in chunks rather
than hitting the file per character. `Files.newBufferedReader` does the wrapping for you.

### 2. A field parser is a small state machine

```java
for (char c : line.toCharArray()) {
    if (inQuotes) {
        if (c == '"') { /* peek: "" is an escaped quote, " ends the field */ }
        else field.append(c);
    } else {
        if (c == ',')      { finish the field }
        else if (c == '"') { inQuotes = true }
        else               { field.append(c) }
    }
}
```

One `boolean` of state and a character loop. That is the whole parser, and writing it
once — rather than reaching for a library — is worth it because the shape recurs: any
format with escaping is some version of this.

For a real project with real CSV, use a library (Apache Commons CSV, OpenCSV). The
edge cases are genuinely fiddly — embedded newlines, BOMs, `\r\n`. Here the goal is to
have written one.

> **Scope limit:** this reader handles quoted commas and escaped quotes, but **not**
> newlines inside quoted fields, because it reads line by line. Sprint 04 caps
> descriptions at 100 characters with no newline, so the writer can never produce one.
> Write that constraint as a comment in the reader — a parser that silently mishandles
> valid input is worse than one that documents what it does not accept.

### 3. Errors that name the line

```java
throw new CsvFormatException(5, "amount must be a number with at most two decimals: 'abc'");
```

The specification requires the import dialog to report *"which line failed and why"*. A
message of "invalid CSV" is worthless when the file has 800 rows.

Carry the line number as a **field**, not only in the message text, so sprint 19 can
display it separately. Count lines the way the user's editor does: the header is line 1,
the first record is line 2.

### 4. Validate everything before writing anything

```java
List<Expense> parsed = reader.read(path);   // throws on the first bad line
store.addAll(parsed, onProgress, cancelled);
```

Parsing completes **before** the transaction opens. That gives you two properties for
free: the error message names the bad line before a single row is written, and the
import transaction is as short as it can be.

The transaction from sprint 09 would roll back anyway. Parsing first means it never
starts — which is both faster and much easier to reason about.

## What you build

- `CsvFormatException` — carries a line number
- `CsvWriter` — one method
- `CsvReader` — the parser
- `ImportService` — parse, then `addAll`

## Definition of done

- [ ] Spec test 6: a file whose fifth line is malformed leaves the database exactly as
      it was
- [ ] A description containing a comma, a quote and both survives export and re-import
- [ ] The error message names the line number and the reason
- [ ] A wrong header is rejected before any row is parsed
- [ ] Neither class in `io` imports anything from `java.sql` or `javafx`

---

## Reading check

1. `line.split(",")` on `2026-09-15,GROCERIES,"Weekly shop, incl. wine",24.90` — how
   many pieces, and which field is wrong?
2. `readLine()` returns `null` at EOF. What does it return for a blank line in the
   middle of a file, and how does your reader tell them apart?
3. Parsing happens before the transaction opens. Name a failure mode that ordering
   prevents, and one it does not.
4. Export omits the `id`. Describe exactly what a user sees if they export September
   and immediately re-import the same file.
