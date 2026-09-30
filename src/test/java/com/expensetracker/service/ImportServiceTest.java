package com.expensetracker.service;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.CategorySpend;
import com.expensetracker.domain.Expense;
import com.expensetracker.domain.ExpenseFilter;
import com.expensetracker.io.CsvFormatException;
import com.expensetracker.io.CsvReader;
import com.expensetracker.io.CsvWriter;
import com.expensetracker.store.Database;
import com.expensetracker.store.ExpenseStore;
import com.expensetracker.store.JdbcExpenseStore;
import com.expensetracker.store.SchemaMigrator;
import com.expensetracker.store.StoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Against a real database: the rollback being tested lives in the JDBC store. */
class ImportServiceTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final String HEADER = "date,category,description,amount";

    @TempDir
    Path tempDir;

    private JdbcExpenseStore store;
    private ImportService importService;

    @BeforeEach
    void setUp() {
        Database db = new Database(tempDir.resolve("test.db"));
        new SchemaMigrator(db).migrate();
        store = new JdbcExpenseStore(db);
        importService = new ImportService(store, new CsvReader(), new CsvWriter());
    }

    private static Expense expense(String amount, Category category, String description, String date) {
        return Expense.create(new BigDecimal(amount), category, description, LocalDate.parse(date));
    }

    private Path write(String... lines) throws IOException {
        Path file = tempDir.resolve("in.csv");
        Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
        return file;
    }

    /** Every test seeds and imports September only, so this is every row. No java.sql needed. */
    private List<Expense> september() {
        return store.find(ExpenseFilter.of(SEPTEMBER));
    }

    // --- import --------------------------------------------------------------

    @Test
    void importAddsEveryRow() throws IOException {
        Path file = write(HEADER,
                "2026-09-01,GROCERIES,one,1.00",
                "2026-09-02,TRANSPORT,\"two, with a comma\",2.00");

        assertEquals(2, importService.importCsv(file, i -> { }, () -> false));
        assertEquals(2, september().size());
    }

    @Test
    void importReportsProgressPerRow() throws IOException {
        Path file = write(HEADER, "2026-09-01,OTHER,a,1.00", "2026-09-02,OTHER,b,2.00", "2026-09-03,OTHER,c,3.00");
        List<Integer> progress = new ArrayList<>();

        importService.importCsv(file, progress::add, () -> false);

        assertEquals(List.of(1, 2, 3), progress);
    }

    /** Spec test 6. The parse throws before addAll is called: no transaction is even opened. */
    @Test
    void aMalformedFifthLineLeavesTheDatabaseExactlyAsItWas() throws IOException {
        store.add(expense("5.00", Category.OTHER, "pre-existing", "2026-09-01"));
        List<Expense> before = september();

        Path file = write(
                HEADER,
                "2026-09-01,GROCERIES,one,1.00",
                "2026-09-02,GROCERIES,two,2.00",
                "2026-09-03,GROCERIES,three,3.00",
                "2026-09-04,GROCERIES,four,NOT_A_NUMBER");

        CsvFormatException thrown = assertThrows(CsvFormatException.class,
                () -> importService.importCsv(file, i -> { }, () -> false));

        assertEquals(5, thrown.line());
        assertEquals(before, september());
    }

    /**
     * The second line of defence: every row parses, and the store rejects one during the
     * insert. The file cannot produce a duplicate id - ids are new on import - so a
     * wrapper slips an already-stored expense into the middle of the batch.
     */
    @Test
    void aDuplicateIdMidImportRollsBack() throws IOException {
        Expense existing = expense("5.00", Category.OTHER, "pre-existing", "2026-09-01");
        store.add(existing);
        List<Expense> before = september();

        ImportService withDuplicate = new ImportService(
                new InjectingStore(store, 2, existing), new CsvReader(), new CsvWriter());
        Path file = write(HEADER,
                "2026-09-01,GROCERIES,one,1.00",
                "2026-09-02,GROCERIES,two,2.00",
                "2026-09-03,GROCERIES,three,3.00");

        assertThrows(StoreException.class, () -> withDuplicate.importCsv(file, i -> { }, () -> false));

        assertEquals(before, september());
    }

    @Test
    void aCancelledImportAddsNothing() throws IOException {
        Path file = write(HEADER, "2026-09-01,OTHER,a,1.00", "2026-09-02,OTHER,b,2.00");
        int[] seen = {0};

        int imported = importService.importCsv(file, i -> seen[0] = i, () -> seen[0] >= 1);

        assertEquals(0, imported);
        assertEquals(List.of(), september());
    }

    /** Import is "add these", not "restore this backup": the documented decision, as a test. */
    @Test
    void exportingThenReimportingDuplicatesEveryExpense() {
        store.add(expense("24.90", Category.GROCERIES, "Weekly shop, incl. wine", "2026-09-15"));
        store.add(expense("8.00", Category.LEISURE, "He said \"hello\"", "2026-09-17"));
        Path file = tempDir.resolve("september.csv");

        importService.exportCsv(file, ExpenseFilter.of(SEPTEMBER));
        importService.importCsv(file, i -> { }, () -> false);

        List<Expense> after = september();
        assertEquals(4, after.size());
        assertEquals(2, after.stream().filter(e -> e.description().equals("He said \"hello\"")).count());
    }

    // --- export uses the filter ---------------------------------------------

    @Test
    void exportWritesOnlyTheFilteredMonth() throws IOException {
        store.add(expense("1.00", Category.OTHER, "september", "2026-09-10"));
        store.add(expense("2.00", Category.OTHER, "august", "2026-08-10"));
        Path file = tempDir.resolve("out.csv");

        importService.exportCsv(file, ExpenseFilter.of(SEPTEMBER));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertEquals("2026-09-10,OTHER,september,1.00", lines.get(1));
    }

    @Test
    void exportWritesOnlyTheFilteredCategory() throws IOException {
        store.add(expense("1.00", Category.GROCERIES, "food", "2026-09-10"));
        store.add(expense("2.00", Category.TRANSPORT, "bus", "2026-09-11"));
        Path file = tempDir.resolve("out.csv");

        importService.exportCsv(file, ExpenseFilter.of(SEPTEMBER, Category.GROCERIES));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertEquals("2026-09-10,GROCERIES,food,1.00", lines.get(1));
    }

    /** One line, not zero: an empty export is still a valid, re-importable file. */
    @Test
    void exportOfAnEmptyFilterWritesJustTheHeader() throws IOException {
        Path file = tempDir.resolve("out.csv");

        importService.exportCsv(file, ExpenseFilter.of(SEPTEMBER));

        assertEquals(List.of(HEADER), Files.readAllLines(file, StandardCharsets.UTF_8));
        assertEquals(0, importService.importCsv(file, i -> { }, () -> false));
    }

    /**
     * Passes every call to the real store, except that {@code addAll} gets one extra
     * expense inserted at {@code position} - one the real store already has.
     */
    private static final class InjectingStore implements ExpenseStore {

        private final ExpenseStore delegate;
        private final int position;
        private final Expense extra;

        InjectingStore(ExpenseStore delegate, int position, Expense extra) {
            this.delegate = delegate;
            this.position = position;
            this.extra = extra;
        }

        @Override
        public int addAll(List<Expense> expenses, IntConsumer onProgress, BooleanSupplier cancelled) {
            List<Expense> withExtra = new ArrayList<>(expenses);
            withExtra.add(position, extra);
            return delegate.addAll(withExtra, onProgress, cancelled);
        }

        @Override
        public void add(Expense expense) {
            delegate.add(expense);
        }

        @Override
        public Optional<Expense> findById(String id) {
            return delegate.findById(id);
        }

        @Override
        public List<Expense> find(ExpenseFilter filter) {
            return delegate.find(filter);
        }

        @Override
        public boolean update(Expense expense) {
            return delegate.update(expense);
        }

        @Override
        public boolean delete(String id) {
            return delegate.delete(id);
        }

        @Override
        public List<CategorySpend> totalsByCategory(LocalDate from, LocalDate to) {
            return delegate.totalsByCategory(from, to);
        }

        @Override
        public List<Expense> findTop(LocalDate from, LocalDate to, int limit) {
            return delegate.findTop(from, to, limit);
        }
    }
}
