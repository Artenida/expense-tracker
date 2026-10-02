package com.expensetracker.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The progress denominator, with no toolkit. */
class ImportExportActionsTest {

    @TempDir
    Path dir;

    @Test
    void expectedRowsCountsRecordsNotLines() throws IOException {
        Path file = dir.resolve("three.csv");
        Files.writeString(file, "date,category,description,amount\n"
                + "2026-09-01,OTHER,a,1.00\n"
                + "2026-09-02,OTHER,b,2.00\n"
                + "2026-09-03,OTHER,c,3.00\n");

        assertEquals(3, ImportExportActions.expectedRows(file));
    }

    /** Never zero: it is a divisor for the progress bar. */
    @Test
    void aHeaderOnlyFileCountsAsOne() throws IOException {
        Path file = dir.resolve("empty.csv");
        Files.writeString(file, "date,category,description,amount\n");

        assertEquals(1, ImportExportActions.expectedRows(file));
    }

    @Test
    void expectedRowsReturnsMinusOneForAnUnreadableFile() {
        assertEquals(-1, ImportExportActions.expectedRows(dir.resolve("missing.csv")));
    }

    /** Invalid UTF-8 makes Files.lines throw UncheckedIOException mid-stream, not IOException. */
    @Test
    void expectedRowsReturnsMinusOneForUndecodableBytes() throws IOException {
        Path file = dir.resolve("binary.csv");
        Files.write(file, new byte[] {'a', '\n', (byte) 0xC3, (byte) 0x28, '\n'});

        assertEquals(-1, ImportExportActions.expectedRows(file));
    }
}
