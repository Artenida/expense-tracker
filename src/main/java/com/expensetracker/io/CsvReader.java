package com.expensetracker.io;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.Expense;
import com.expensetracker.domain.Money;
import com.expensetracker.domain.ValidationException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the format {@link CsvWriter} writes, and rejects anything else with the line
 * number and the reason - "invalid CSV" is worthless in an 800-row file.
 *
 * <p><strong>Does not accept newlines inside quoted fields.</strong> It reads line by
 * line, so a quoted field spanning two lines is reported as an unterminated quote.
 * Sprint 04 caps descriptions at 100 characters with no newline, so the writer never
 * produces one; a file from elsewhere that has one is rejected, not misread.
 *
 * <p>Returns the whole file or throws: nothing is half-read, so the caller can parse
 * everything before it writes anything.
 */
public final class CsvReader {

    private static final List<String> EXPECTED_HEADER =
            List.of("date", "category", "description", "amount");

    /** Excel's "CSV UTF-8" starts the file with one; it is not part of the first column name. */
    private static final String BOM = "﻿";

    /**
     * @throws CsvFormatException   if the header is wrong or any record is malformed or
     *                              invalid; nothing is returned in that case
     * @throws UncheckedIOException if the file cannot be read
     */
    public List<Expense> read(Path path) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                throw new CsvFormatException(1, "file is empty");
            }
            checkHeader(header);

            List<Expense> parsed = new ArrayList<>();
            int n = 1;
            String line;
            // null is end of file; a blank line mid-file is "" and is skipped.
            while ((line = reader.readLine()) != null) {
                n++;
                if (line.isBlank()) {
                    continue;
                }
                parsed.add(toExpense(n, line));
            }
            return List.copyOf(parsed);
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to read " + path, ex);
        }
    }

    /**
     * Checked before any record is parsed. Columns in the wrong order would put amounts
     * where dates go - a corruption, not a parse error - so the order is enforced.
     */
    private static void checkHeader(String header) {
        String line = header.startsWith(BOM) ? header.substring(1) : header;
        List<String> found = parseFieldsOrReport(1, line).stream()
                .map(f -> f.trim().toLowerCase(Locale.ROOT))
                .toList();
        if (!found.equals(EXPECTED_HEADER)) {
            throw new CsvFormatException(1,
                    "expected header '" + CsvWriter.HEADER + "' but found '" + line + "'");
        }
    }

    /**
     * One {@code catch} per conversion, because each needs its own message, and the
     * message is the deliverable. The domain rules - positive amount, no future date,
     * description length - come from {@code Expense.create}, not a copy of them here.
     */
    private static Expense toExpense(int n, String line) {
        List<String> fields = parseFieldsOrReport(n, line);
        if (fields.size() != EXPECTED_HEADER.size()) {
            throw new CsvFormatException(n,
                    "expected " + EXPECTED_HEADER.size() + " fields, found " + fields.size());
        }

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
            throw new CsvFormatException(n, ex.getMessage());     // already lists the valid names
        }

        BigDecimal amount;
        try {
            amount = Money.parse(fields.get(3));
        } catch (IllegalArgumentException ex) {
            throw new CsvFormatException(n,
                    "amount must be a plain decimal with at most two places: '" + fields.get(3) + "'");
        }

        try {
            return Expense.create(amount, category, fields.get(2), date);
        } catch (ValidationException ex) {
            throw new CsvFormatException(n, String.join("; ", ex.errors()));
        }
    }

    private static List<String> parseFieldsOrReport(int n, String line) {
        try {
            return parseFields(line);
        } catch (IllegalArgumentException ex) {
            throw new CsvFormatException(n, ex.getMessage());
        }
    }

    /**
     * Splits one line into fields: a state machine with one {@code boolean} of state.
     * Package-private so it can be tested without a file.
     *
     * @throws IllegalArgumentException if a quoted field is never closed
     */
    static List<String> parseFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        // An index rather than for-each: an escaped quote needs to look at the next
        // character and skip it.
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;                           // consume the second quote
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

        // Otherwise an unclosed quote silently swallows the rest of the line into one field.
        if (inQuotes) {
            throw new IllegalArgumentException("a quoted field is not closed");
        }
        fields.add(field.toString());                  // a,b,c: two commas, three fields
        return fields;
    }
}
