# Sprint 12 · Tests

`io/CsvReaderTest.java`, `io/CsvWriterTest.java`, `service/ImportServiceTest.java`

Covers **spec test 6**.

---

## The field parser, in isolation

Start here. It is the piece most likely to be wrong, and it needs no file:

```java
@ParameterizedTest
@CsvSource(delimiter = '|', value = {
    "a,b,c                        | a;b;c",
    "a,,c                         | a;;c",
    ",,                           | ;;;",
    "\"a,b\",c                    | a,b;c",
    "\"he said \"\"hi\"\"\",x     | he said \"hi\";x",
    "\"\",x                       | ;x",
    "plain                        | plain",
    "trailing,                    | trailing;"
})
void parseFields(String line, String expectedJoinedBySemicolon) {
    assertEquals(List.of(expectedJoinedBySemicolon.split(";", -1)),
                 CsvReader.parseFields(line.trim()));
}
```

`split(";", -1)` with the negative limit keeps trailing empty strings — without it,
`"trailing;"` becomes a one-element array and the test asserts the wrong thing. A small
trap, and exactly the same off-by-one the parser itself has.

The `,,` case (three empty fields) and `trailing,` (two fields, second empty) are the
ones that catch a missing final `add`.

## Writer

| Test | Proves |
|---|---|
| `writesTheHeader` | First line is `date,category,description,amount` |
| `writesOneLinePerExpense` | Line count is `expenses + 1` |
| `plainFieldsAreNotQuoted` | `bus fare` appears bare — readable output |
| `commasAreQuoted` | `Weekly shop, incl. wine` → `"Weekly shop, incl. wine"` |
| `quotesAreDoubled` | `He said "hi"` → `"He said ""hi"""` |
| `amountsAlwaysHaveTwoDecimals` | `24.00`, never `24` |
| `categoryIsTheEnumName` | `GROCERIES`, not `Groceries` |
| `datesAreIso` | `2026-09-15` |
| `noIdColumnIsWritten` | The header has four fields; the decision, enforced |

## Round trip — the requirement

```java
@ParameterizedTest
@ValueSource(strings = {
    "plain description",
    "with, a comma",
    "with \"quotes\"",
    "both, \"together\"",
    "\"leading quote",
    "trailing comma,",
    "café ☕ unicode",
    "   leading and trailing spaces   "
})
void everyDescriptionSurvivesARoundTrip(String description) throws IOException {
    Path file = tempDir.resolve("roundtrip.csv");
    Expense original = Expense.create(new BigDecimal("24.90"), Category.GROCERIES,
                                      description, LocalDate.of(2026, 9, 15));

    new CsvWriter().write(file, List.of(original));
    List<Expense> read = new CsvReader().read(file);

    assertEquals(1, read.size());
    assertEquals(original.description().trim(), read.get(0).description());
    assertEquals(0, original.amount().compareTo(read.get(0).amount()));
    assertEquals(original.date(), read.get(0).date());
    assertEquals(original.category(), read.get(0).category());
}
```

The ids will differ — that is the documented decision, and the test asserts on the four
fields that must survive rather than on the whole object.

`café ☕` is the charset test. Write it as UTF-8, read it as UTF-8, get it back. Change
the writer to `new FileWriter(...)` and watch it break on a machine whose default
encoding is not UTF-8.

The `   leading and trailing spaces   ` case has an expected answer you must decide:
`Expense.create` trims, so the round trip gives you the trimmed version. Asserting
`original.description().trim()` documents that rather than glossing over it.

## Reader — rejection, with line numbers

| File | Expected |
|---|---|
| Empty file | `CsvFormatException`, line 1, "empty" |
| Header `date,cat,desc,amt` | line 1, mentions the expected header |
| Header in a different order | line 1 — **not** silently accepted |
| Record with 3 fields | line n, "expected 4 fields, found 3" |
| Record with 5 fields | line n, "found 5" |
| `notadate,GROCERIES,x,1.00` | line n, mentions the date format |
| `2026-09-15,FOOD,x,1.00` | line n, **lists the valid categories** |
| `2026-09-15,GROCERIES,x,abc` | line n, mentions the amount format |
| `2026-09-15,GROCERIES,x,1.234` | line n — sprint 03's rule reaches the importer |
| `2026-09-15,GROCERIES,,1.00` | line n, "description is required" |
| A date in the future | line n, "date cannot be in the future" |

Assert on **`line()`**, not just the exception type:

```java
@Test
void theFifthLineIsReportedAsTheFifthLine() throws IOException {
    Path file = write(
        "date,category,description,amount",     // 1
        "2026-09-01,GROCERIES,one,1.00",        // 2
        "2026-09-02,GROCERIES,two,2.00",        // 3
        "2026-09-03,GROCERIES,three,3.00",      // 4
        "2026-09-04,NOPE,four,4.00");           // 5

    CsvFormatException thrown = assertThrows(CsvFormatException.class,
        () -> new CsvReader().read(file));

    assertEquals(5, thrown.line());
}
```

Counting from the header as line 1 is what matches the user's editor. Get it wrong and
every error message points one line off, which is worse than no line number at all
because it sends people to the wrong row.

| Test | Proves |
|---|---|
| `blankLinesAreSkipped` | A trailing newline does not become a parse error |
| `windowsLineEndingsAreAccepted` | `\r\n` — `readLine` strips it, but assert it |

## Spec test 6 — the database is unchanged

```java
@Test
void aMalformedFifthLineLeavesTheDatabaseExactlyAsItWas() throws IOException {
    store.add(expense("5.00", Category.OTHER, "pre-existing", "2026-09-01"));
    long before = countRows();

    Path file = write(
        "date,category,description,amount",
        "2026-09-01,GROCERIES,one,1.00",
        "2026-09-02,GROCERIES,two,2.00",
        "2026-09-03,GROCERIES,three,3.00",
        "2026-09-04,GROCERIES,four,NOT_A_NUMBER");

    assertThrows(CsvFormatException.class,
        () -> importService.importCsv(file, i -> {}, () -> false));

    assertEquals(before, countRows());
}
```

Note where the protection actually comes from: the parse throws before `addAll` is
called, so **no transaction is even opened**. Sprint 09's rollback is the second line of
defence, not the first.

Worth proving the second line works too — a store that rejects a row the parser let
through:

```java
@Test
void aDuplicateIdMidImportRollsBack() {
    // parses fine, fails at insert
    ...
    assertEquals(before, countRows());
}
```

## Export uses the filter

| Test | Proves |
|---|---|
| `exportWritesOnlyTheFilteredMonth` | Seed two months, export one, count lines |
| `exportWritesOnlyTheFilteredCategory` | Same for the category filter |
| `exportOfAnEmptyFilterWritesJustTheHeader` | One line, not zero — an empty export is still a valid file |
