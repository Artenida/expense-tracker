# Sprint 12 · Build specification

`io/` for the format, `service/` for the orchestration.

---

## `io/CsvFormatException.java`

```java
public class CsvFormatException extends RuntimeException {

    private final int line;

    public CsvFormatException(int line, String problem) {
        super("line " + line + ": " + problem);
        this.line = line;
    }

    public int line() { return line; }
}
```

---

## `io/CsvWriter.java`

```java
public final class CsvWriter {

    private static final String HEADER = "date,category,description,amount";

    public void write(Path path, List<Expense> expenses) {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write(HEADER);
            out.newLine();

            for (Expense e : expenses) {
                out.write(String.join(",",
                    e.date().toString(),
                    e.category().name(),
                    quote(e.description()),
                    Money.format(e.amount())));
                out.newLine();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to write " + path, ex);
        }
    }
}
```

### `quote`

```java
private static String quote(String field) {
    if (field.indexOf(',') < 0 && field.indexOf('"') < 0 && field.indexOf('\n') < 0) {
        return field;
    }
    return '"' + field.replace("\"", "\"\"") + '"';
}
```

Quote only when needed — RFC 4180 allows either, and unquoted fields are far easier to
read when you open the file to check something. The `replace` doubles every quote,
which is how the format escapes them.

`out.newLine()` rather than `"\n"`: it uses the platform line separator, so the file
opens correctly in Excel on Windows. The reader accepts both.

`UncheckedIOException` is a JDK type that exists for exactly this — wrapping a checked
`IOException` when the method cannot usefully declare it. Same reasoning as
`StoreException` in sprint 06, with the wrapper already written for you.

---

## `io/CsvReader.java`

```java
public final class CsvReader {

    private static final List<String> EXPECTED_HEADER =
        List.of("date", "category", "description", "amount");

    public List<Expense> read(Path path) { ... }
}
```

### `read`

```
1. open a BufferedReader on UTF-8
2. read line 1; parse fields; compare (lowercased, trimmed) to EXPECTED_HEADER
     mismatch  → CsvFormatException(1, "expected header 'date,category,...' but found '...'")
     no lines  → CsvFormatException(1, "file is empty")
3. for each subsequent line, tracking the number (starting at 2):
     a. skip if blank
     b. parseFields → must give exactly 4; otherwise CsvFormatException(n, "expected 4 fields, found 6")
     c. convert each field, wrapping any failure in CsvFormatException(n, reason)
     d. Expense.create(...) — wrap ValidationException in CsvFormatException(n, ...)
4. return List.copyOf(parsed)
```

### Converting a record line

```java
LocalDate date;
try {
    date = LocalDate.parse(fields.get(0).trim());
} catch (DateTimeParseException ex) {
    throw new CsvFormatException(n, "date must be yyyy-MM-dd: '" + fields.get(0) + "'");
}

Category category;
try {
    category = Category.parse(fields.get(1));
} catch (IllegalArgumentException ex) {
    throw new CsvFormatException(n, ex.getMessage());     // already lists valid names
}

BigDecimal amount;
try {
    amount = Money.parse(fields.get(3));
} catch (IllegalArgumentException ex) {
    throw new CsvFormatException(n, "amount must be a plain decimal: '" + fields.get(3) + "'");
}

try {
    return Expense.create(amount, category, fields.get(2), date);
} catch (ValidationException ex) {
    throw new CsvFormatException(n, String.join("; ", ex.errors()));
}
```

Verbose, and deliberately so. Each conversion gets its own `catch` because each one
needs a different message, and **the message is the deliverable**. A single
`catch (Exception e)` around the whole block would produce "line 5: something went
wrong", which fails the specification's requirement.

`Category.parse`'s message already lists the valid categories — sprint 02 wrote it for
exactly this moment. Pass it straight through.

`Expense.create` runs the full domain validation, so the future-date rule, the
description length and the positive-amount rule all apply to imported rows. The importer
does not re-implement any of them.

### `parseFields` — the state machine

```java
static List<String> parseFields(String line) {
    List<String> fields = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean inQuotes = false;

    for (int i = 0; i < line.length(); i++) {
        char c = line.charAt(i);

        if (inQuotes) {
            if (c == '"') {
                if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;                       // consume the second quote
                } else {
                    inQuotes = false;
                }
            } else {
                field.append(c);
            }
        } else if (c == '"') {
            inQuotes = true;
        } else if (c == ',') {
            fields.add(field.toString());
            field.setLength(0);
        } else {
            field.append(c);
        }
    }

    fields.add(field.toString());              // the last field has no trailing comma
    return fields;
}
```

Package-private (not `private`) so it can be tested directly. It is the piece most
likely to be wrong and the easiest to test in isolation — thirty single-line cases in
one parameterised test.

The final `fields.add` outside the loop is the classic off-by-one in this shape: `a,b,c`
has two commas and three fields.

`line.charAt(i)` with an explicit index rather than a for-each, because the escaped-quote
case needs to look at the *next* character and skip it.

---

## `service/ImportService.java`

```java
public final class ImportService {

    private final ExpenseStore store;
    private final CsvReader    reader;
    private final CsvWriter    writer;

    public int importCsv(Path path, IntConsumer onProgress, BooleanSupplier cancelled) {
        List<Expense> parsed = reader.read(path);          // throws before anything is written
        return store.addAll(parsed, onProgress, cancelled);
    }

    public void exportCsv(Path path, ExpenseFilter filter) {
        writer.write(path, store.find(filter));
    }
}
```

Five lines of orchestration, and every hard requirement is met by something it calls:

| Requirement | Met by |
|---|---|
| One bad line rolls the whole import back | `read` throws before `addAll` opens a transaction |
| The dialog reports which line and why | `CsvFormatException.line()` and `getMessage()` |
| Cancelling leaves the database unchanged | Sprint 09's rollback-on-cancel |
| The exported file can be re-imported | The writer's `quote` and the reader's parser agreeing |

`exportCsv` takes an `ExpenseFilter` so it exports **what the user is looking at**, not
the whole database. That matches the toolbar sitting above the filtered table in sprint
16.

Progress during parse is not reported — only during insert. For a file small enough to
hold in memory the parse is effectively instant, and reporting both phases would need a
two-stage progress model for no visible benefit. Worth knowing you made that call.
