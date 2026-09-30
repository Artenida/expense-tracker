package com.expensetracker.io;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.Expense;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvReaderTest {

    private static final String HEADER = "date,category,description,amount";

    @TempDir
    Path tempDir;

    private Path write(String... lines) throws IOException {
        Path file = tempDir.resolve("in.csv");
        Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
        return file;
    }

    private CsvFormatException rejected(String... lines) throws IOException {
        Path file = write(lines);
        return assertThrows(CsvFormatException.class, () -> new CsvReader().read(file));
    }

    // --- the field parser, in isolation --------------------------------------

    /**
     * Expected fields joined by ';'. {@code split(";", -1)} keeps trailing empty strings;
     * without the -1, "trailing;" would be one element and the test would assert the
     * same off-by-one the parser is prone to. {@code ,,} is three empty fields, so ";;".
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "a,b,c                        | a;b;c",
            "a,,c                         | a;;c",
            ",,                           | ;;",
            "\"a,b\",c                    | a,b;c",
            "\"he said \"\"hi\"\"\",x     | he said \"hi\";x",
            "\"\",x                       | ;x",
            "plain                        | plain",
            "trailing,                    | trailing;",
            "\"\"\"\",x                   | \";x",
            "a,\"b,c\",\"d\"\"e\",f       | a;b,c;d\"e;f"
    })
    void parseFields(String line, String expectedJoinedBySemicolon) {
        assertEquals(List.of(expectedJoinedBySemicolon.split(";", -1)),
                CsvReader.parseFields(line.trim()));
    }

    @Test
    void anEmptyLineIsOneEmptyField() {
        assertEquals(List.of(""), CsvReader.parseFields(""));
    }

    @Test
    void anUnclosedQuoteIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CsvReader.parseFields("a,\"b,c"));
    }

    // --- reading a valid file ------------------------------------------------

    @Test
    void readsEveryFieldOfARecord() throws IOException {
        Path file = write(HEADER, "2026-09-15,GROCERIES,\"Weekly shop, incl. wine\",24.90");

        Expense e = new CsvReader().read(file).get(0);

        assertEquals(LocalDate.of(2026, 9, 15), e.date());
        assertEquals(Category.GROCERIES, e.category());
        assertEquals("Weekly shop, incl. wine", e.description());
        assertEquals(new BigDecimal("24.90"), e.amount());
    }

    @Test
    void blankLinesAreSkipped() throws IOException {
        Path file = write(HEADER, "2026-09-01,OTHER,one,1.00", "", "   ", "2026-09-02,OTHER,two,2.00", "");

        assertEquals(2, new CsvReader().read(file).size());
    }

    @Test
    void windowsLineEndingsAreAccepted() throws IOException {
        Path file = tempDir.resolve("crlf.csv");
        Files.writeString(file, HEADER + "\r\n2026-09-01,OTHER,one,1.00\r\n", StandardCharsets.UTF_8);

        Expense e = new CsvReader().read(file).get(0);

        assertEquals(new BigDecimal("1.00"), e.amount());
    }

    @Test
    void theHeaderIsCaseInsensitive() throws IOException {
        assertEquals(1, new CsvReader().read(write("Date, Category, Description, Amount",
                "2026-09-01,OTHER,one,1.00")).size());
    }

    /** Excel's "CSV UTF-8" writes a byte-order mark before the header. */
    @Test
    void aByteOrderMarkIsIgnored() throws IOException {
        assertEquals(1, new CsvReader().read(write("﻿" + HEADER, "2026-09-01,OTHER,one,1.00")).size());
    }

    @Test
    void aHeaderOnlyFileIsNoExpenses() throws IOException {
        assertTrue(new CsvReader().read(write(HEADER)).isEmpty());
    }

    // --- rejection, with line numbers -----------------------------------------

    @Test
    void anEmptyFileIsRejected() throws IOException {
        CsvFormatException e = rejected();

        assertEquals(1, e.line());
        assertTrue(e.getMessage().contains("empty"), e.getMessage());
    }

    @Test
    void aWrongHeaderIsRejected() throws IOException {
        CsvFormatException e = rejected("date,cat,desc,amt", "2026-09-01,OTHER,one,1.00");

        assertEquals(1, e.line());
        assertTrue(e.getMessage().contains(HEADER), e.getMessage());
    }

    /** Columns in another order would put amounts where dates go. */
    @Test
    void aReorderedHeaderIsRejected() throws IOException {
        CsvFormatException e = rejected("amount,date,category,description", "1.00,2026-09-01,OTHER,one");

        assertEquals(1, e.line());
    }

    /** The header is checked first, so a bad record under a bad header reports line 1. */
    @Test
    void aWrongHeaderIsRejectedBeforeAnyRowIsParsed() throws IOException {
        assertEquals(1, rejected("date,category,amount", "garbage").line());
    }

    @Test
    void aRecordWithThreeFieldsIsRejected() throws IOException {
        CsvFormatException e = rejected(HEADER, "2026-09-01,OTHER,1.00");

        assertEquals(2, e.line());
        assertTrue(e.getMessage().contains("expected 4 fields, found 3"), e.getMessage());
    }

    @Test
    void aRecordWithFiveFieldsIsRejected() throws IOException {
        CsvFormatException e = rejected(HEADER, "2026-09-01,OTHER,shop, incl. wine,1.00");

        assertEquals(2, e.line());
        assertTrue(e.getMessage().contains("found 5"), e.getMessage());
    }

    @Test
    void aBadDateIsRejected() throws IOException {
        CsvFormatException e = rejected(HEADER, "notadate,GROCERIES,x,1.00");

        assertEquals(2, e.line());
        assertTrue(e.getMessage().contains("yyyy-MM-dd"), e.getMessage());
    }

    @Test
    void anUnknownCategoryListsTheValidOnes() throws IOException {
        CsvFormatException e = rejected(HEADER, "2026-09-15,FOOD,x,1.00");

        assertEquals(2, e.line());
        for (Category c : Category.values()) {
            assertTrue(e.getMessage().contains(c.name()), e.getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.234", "-1.00", "\"1,00\"", "1e3"})
    void aBadAmountIsRejected(String amount) throws IOException {
        CsvFormatException e = rejected(HEADER, "2026-09-15,GROCERIES,x," + amount);

        assertEquals(2, e.line());
        assertTrue(e.getMessage().contains("amount"), e.getMessage());
    }

    @Test
    void aMissingDescriptionIsRejected() throws IOException {
        CsvFormatException e = rejected(HEADER, "2026-09-15,GROCERIES,,1.00");

        assertEquals(2, e.line());
        assertTrue(e.getMessage().contains("description is required"), e.getMessage());
    }

    @Test
    void aFutureDateIsRejected() throws IOException {
        CsvFormatException e = rejected(HEADER, LocalDate.now().plusDays(1) + ",GROCERIES,x,1.00");

        assertEquals(2, e.line());
        assertTrue(e.getMessage().contains("date cannot be in the future"), e.getMessage());
    }

    @Test
    void aZeroAmountIsRejectedByTheDomainRule() throws IOException {
        assertEquals(2, rejected(HEADER, "2026-09-15,GROCERIES,x,0.00").line());
    }

    @Test
    void anUnclosedQuoteNamesTheLine() throws IOException {
        CsvFormatException e = rejected(HEADER, "2026-09-15,GROCERIES,\"never closed,1.00");

        assertEquals(2, e.line());
        assertTrue(e.getMessage().contains("quoted"), e.getMessage());
    }

    @Test
    void theFifthLineIsReportedAsTheFifthLine() throws IOException {
        CsvFormatException e = rejected(
                HEADER,                                 // 1
                "2026-09-01,GROCERIES,one,1.00",        // 2
                "2026-09-02,GROCERIES,two,2.00",        // 3
                "2026-09-03,GROCERIES,three,3.00",      // 4
                "2026-09-04,NOPE,four,4.00");           // 5

        assertEquals(5, e.line());
        assertTrue(e.getMessage().startsWith("line 5: "), e.getMessage());
    }

    /** Skipped blank lines still count, or every later error points one line off. */
    @Test
    void blankLinesStillCountTowardsTheLineNumber() throws IOException {
        assertEquals(4, rejected(HEADER, "2026-09-01,OTHER,one,1.00", "", "2026-09-04,NOPE,x,1.00").line());
    }
}
