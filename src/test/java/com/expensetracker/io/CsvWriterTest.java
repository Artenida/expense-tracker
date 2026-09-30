package com.expensetracker.io;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.Expense;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvWriterTest {

    @TempDir
    Path tempDir;

    private static Expense expense(String amount, Category category, String description) {
        return Expense.create(new BigDecimal(amount), category, description, LocalDate.of(2026, 9, 15));
    }

    /** Writes, then reads back the raw lines - what someone opening the file would see. */
    private List<String> writeAndReadLines(Expense... expenses) throws IOException {
        Path file = tempDir.resolve("out.csv");
        new CsvWriter().write(file, List.of(expenses));
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    @Test
    void writesTheHeader() throws IOException {
        assertEquals("date,category,description,amount", writeAndReadLines().get(0));
    }

    @Test
    void writesOneLinePerExpense() throws IOException {
        List<String> lines = writeAndReadLines(
                expense("1.00", Category.OTHER, "one"),
                expense("2.00", Category.OTHER, "two"),
                expense("3.00", Category.OTHER, "three"));

        assertEquals(4, lines.size());
    }

    @Test
    void plainFieldsAreNotQuoted() throws IOException {
        assertEquals("2026-09-15,TRANSPORT,bus fare,2.50",
                writeAndReadLines(expense("2.50", Category.TRANSPORT, "bus fare")).get(1));
    }

    @Test
    void commasAreQuoted() throws IOException {
        assertEquals("2026-09-15,GROCERIES,\"Weekly shop, incl. wine\",24.90",
                writeAndReadLines(expense("24.90", Category.GROCERIES, "Weekly shop, incl. wine")).get(1));
    }

    @Test
    void quotesAreDoubled() throws IOException {
        assertEquals("2026-09-15,LEISURE,\"He said \"\"hi\"\"\",8.00",
                writeAndReadLines(expense("8.00", Category.LEISURE, "He said \"hi\"")).get(1));
    }

    @Test
    void amountsAlwaysHaveTwoDecimals() throws IOException {
        String line = writeAndReadLines(expense("24", Category.OTHER, "x")).get(1);

        assertEquals("24.00", line.substring(line.lastIndexOf(',') + 1));
    }

    @Test
    void categoryIsTheEnumName() throws IOException {
        String line = writeAndReadLines(expense("1.00", Category.GROCERIES, "x")).get(1);

        assertEquals("GROCERIES", line.split(",")[1]);
    }

    @Test
    void datesAreIso() throws IOException {
        String line = writeAndReadLines(expense("1.00", Category.OTHER, "x")).get(1);

        assertEquals("2026-09-15", line.split(",")[0]);
    }

    /** The decision, enforced: four columns, and the id appears nowhere in the row. */
    @Test
    void noIdColumnIsWritten() throws IOException {
        Expense e = expense("1.00", Category.OTHER, "x");
        List<String> lines = writeAndReadLines(e);

        assertEquals(4, lines.get(0).split(",").length);
        assertEquals(4, lines.get(1).split(",").length);
        assertEquals(-1, lines.get(1).indexOf(e.id()));
    }
}
