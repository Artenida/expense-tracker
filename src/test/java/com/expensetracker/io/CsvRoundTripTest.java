package com.expensetracker.io;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.Expense;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The requirement: an export can be re-imported with no loss of what the user entered. */
class CsvRoundTripTest {

    @TempDir
    Path tempDir;

    /**
     * Asserts the four fields that must survive rather than the whole object: the ids
     * differ, which is the documented decision. Expected description is the trimmed
     * one, because {@code Expense.create} trims - stated here rather than glossed over.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "plain description",
            "with, a comma",
            "with \"quotes\"",
            "both, \"together\"",
            "\"leading quote",
            "trailing quote\"",
            "trailing comma,",
            ",leading comma",
            "\"\"",
            "café ☕ unicode",
            "   leading and trailing spaces   "
    })
    void everyDescriptionSurvivesARoundTrip(String description) {
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

    @Test
    void everyCategoryAndALargeAmountSurvive() {
        Path file = tempDir.resolve("many.csv");
        List<Expense> originals = Arrays.stream(Category.values())
                .map(c -> Expense.create(new BigDecimal("1234567.05"), c, c.name(), LocalDate.of(2025, 1, 31)))
                .toList();

        new CsvWriter().write(file, originals);
        List<Expense> read = new CsvReader().read(file);

        assertEquals(originals.size(), read.size());
        for (int i = 0; i < originals.size(); i++) {
            assertEquals(originals.get(i).category(), read.get(i).category());
            assertEquals(originals.get(i).amount(), read.get(i).amount());
        }
    }

    @Test
    void reimportedExpensesGetNewIds() {
        Path file = tempDir.resolve("ids.csv");
        Expense original = Expense.create(BigDecimal.TEN, Category.OTHER, "x", LocalDate.of(2026, 9, 1));

        new CsvWriter().write(file, List.of(original));

        assertNotEquals(original.id(), new CsvReader().read(file).get(0).id());
    }
}
