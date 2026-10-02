package com.expensetracker.ui;

import com.expensetracker.domain.Budget;
import com.expensetracker.domain.Category;
import com.expensetracker.domain.CategorySpend;
import com.expensetracker.domain.Expense;
import com.expensetracker.domain.ExpenseFilter;
import com.expensetracker.io.CsvReader;
import com.expensetracker.io.CsvWriter;
import com.expensetracker.service.BudgetService;
import com.expensetracker.service.ExpenseService;
import com.expensetracker.service.ImportService;
import com.expensetracker.service.SummaryService;
import com.expensetracker.store.BudgetStore;
import com.expensetracker.store.ExpenseStore;
import com.expensetracker.ui.task.BackgroundRunner;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MainView with real services over recording stores: what reaches the store is what
 * reload() asked for. Tagged ui - the view builds controls and its callbacks run on
 * the FX thread.
 */
@Tag("ui")
@ExtendWith(FxToolkit.class)
class MainViewTest {

    private static final long TIMEOUT_SECONDS = 5;

    private final RecordingExpenseStore store = new RecordingExpenseStore();
    private BackgroundRunner runner;
    private MainView view;

    @BeforeEach
    void setUp() throws Exception {
        runner = new BackgroundRunner();
        BudgetStore budgets = new EmptyBudgetStore();
        view = onFxThread(() -> new MainView(
                new ExpenseService(store),
                new BudgetService(budgets),
                new SummaryService(store, budgets),
                new ImportService(store, new CsvReader(), new CsvWriter()),
                runner,
                Path.of("test.db"), 2));
    }

    @AfterEach
    void tearDown() {
        runner.shutdown();
    }

    @Test
    void theFirstLoadUsesThisMonth() throws Exception {
        assertEquals(YearMonth.now(), nextSummaryMonth());
        assertEquals(ExpenseFilter.of(YearMonth.now()), nextFind());
    }

    @Test
    void summaryServiceIsCalledWithTheSelectedMonth() throws Exception {
        nextSummaryMonth();     // the first load
        YearMonth september = YearMonth.of(2026, 9);

        onFxThread(() -> {
            view.filterPanel().select(september, null);
            return null;
        });

        assertEquals(september, nextSummaryMonth());
    }

    @Test
    void theTableQueryCarriesBothCriteria() throws Exception {
        nextFind();             // the first load
        YearMonth september = YearMonth.of(2026, 9);

        onFxThread(() -> {
            view.filterPanel().select(september, Category.GROCERIES);
            return null;
        });

        // select() fires twice - month, then category - so the last query is the combined one.
        ExpenseFilter last = nextFind();
        ExpenseFilter combined = ExpenseFilter.of(september, Category.GROCERIES);
        while (!last.equals(combined)) {
            last = nextFind();
        }
        assertEquals(combined, last);
    }

    // --- helpers ------------------------------------------------------------

    private YearMonth nextSummaryMonth() throws InterruptedException {
        LocalDate from = poll(store.summaryFroms);
        return YearMonth.from(from);
    }

    private ExpenseFilter nextFind() throws InterruptedException {
        return poll(store.finds);
    }

    private static <T> T poll(BlockingQueue<T> queue) throws InterruptedException {
        T value = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (value == null) {
            throw new AssertionError("no store call within " + TIMEOUT_SECONDS + "s");
        }
        return value;
    }

    private static <T> T onFxThread(Supplier<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Records the queries; called on the worker thread, read by the test thread. */
    private static final class RecordingExpenseStore implements ExpenseStore {

        final BlockingQueue<ExpenseFilter> finds = new LinkedBlockingQueue<>();
        final BlockingQueue<LocalDate> summaryFroms = new LinkedBlockingQueue<>();

        @Override
        public List<Expense> find(ExpenseFilter filter) {
            finds.add(filter);
            return List.of();
        }

        @Override
        public List<CategorySpend> totalsByCategory(LocalDate from, LocalDate to) {
            summaryFroms.add(from);
            return List.of();
        }

        @Override
        public List<Expense> findTop(LocalDate from, LocalDate to, int limit) {
            return List.of();
        }

        @Override
        public void add(Expense expense) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Expense> findById(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean update(Expense expense) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean delete(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int addAll(List<Expense> expenses, IntConsumer onProgress, BooleanSupplier cancelled) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class EmptyBudgetStore implements BudgetStore {

        @Override
        public void upsert(Budget budget) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Budget> findAll() {
            return List.of();
        }

        @Override
        public Optional<Budget> findByCategory(Category category) {
            return Optional.empty();
        }
    }
}
