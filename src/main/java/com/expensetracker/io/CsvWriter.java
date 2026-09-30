package com.expensetracker.io;

import com.expensetracker.domain.Expense;
import com.expensetracker.domain.Money;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes expenses as {@code date,category,description,amount}, RFC 4180 quoting, UTF-8.
 *
 * <p>No id column: an export is what the user spent, not the application's bookkeeping.
 * Re-importing it adds new expenses rather than restoring the old ones - see the README.
 */
public final class CsvWriter {

    static final String HEADER = "date,category,description,amount";

    /**
     * Replaces the file if it exists.
     *
     * @throws UncheckedIOException if the file cannot be written
     */
    public void write(Path path, List<Expense> expenses) {
        // Charset named: a platform default differs between machines and mangles "café".
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write(HEADER);
            out.newLine();

            for (Expense e : expenses) {
                out.write(String.join(",",
                        e.date().toString(),
                        e.category().name(),
                        quote(e.description()),
                        Money.format(e.amount())));
                // The platform separator, so the file opens cleanly in Excel on Windows.
                // The reader accepts both.
                out.newLine();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to write " + path, ex);
        }
    }

    /**
     * Quoted only when it has to be, so the file stays readable when opened by hand.
     * Doubling every quote is how the format escapes one.
     */
    static String quote(String field) {
        if (field.indexOf(',') < 0 && field.indexOf('"') < 0 && field.indexOf('\n') < 0) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }
}
