package com.expensetracker.service;

import com.expensetracker.domain.Expense;
import com.expensetracker.domain.ExpenseFilter;
import com.expensetracker.io.CsvReader;
import com.expensetracker.io.CsvWriter;
import com.expensetracker.store.ExpenseStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * CSV in and out. Orchestration only: every hard requirement is met by something it
 * calls - the reader's line numbers, the store's all-or-nothing {@code addAll}, and the
 * writer and reader agreeing on the format.
 */
public final class ImportService {

    private final ExpenseStore store;
    private final CsvReader reader;
    private final CsvWriter writer;

    public ImportService(ExpenseStore store, CsvReader reader, CsvWriter writer) {
        this.store = Objects.requireNonNull(store, "store");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /**
     * Parses the whole file first, then inserts in one transaction. A bad line throws
     * before the transaction opens, so there is nothing to roll back; sprint 09's
     * rollback covers a failure during the insert itself.
     *
     * <p>Progress is reported for the insert only. Parsing a file small enough to hold
     * in memory is effectively instant, and a two-phase progress bar would show nothing.
     *
     * @return the number imported, or 0 if cancelled
     * @throws com.expensetracker.io.CsvFormatException with the line number, if any line is bad
     */
    public int importCsv(Path path, IntConsumer onProgress, BooleanSupplier cancelled) {
        List<Expense> parsed = reader.read(path);
        return store.addAll(parsed, onProgress, cancelled);
    }

    /** Exports what the user is looking at, not the whole database. */
    public void exportCsv(Path path, ExpenseFilter filter) {
        writer.write(path, store.find(filter));
    }
}
