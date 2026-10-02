package com.expensetracker.ui;

import com.expensetracker.domain.ExpenseFilter;
import com.expensetracker.service.BudgetService;
import com.expensetracker.service.ExpenseService;
import com.expensetracker.service.ImportService;
import com.expensetracker.service.SummaryService;
import com.expensetracker.ui.task.BackgroundRunner;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The window's layout: filters left, table centre, summary right, status bar bottom.
 * The centre takes whatever width is left over - that is {@code BorderPane}'s policy,
 * not something configured here.
 *
 * <p>Holds services, never stores. Everything that changes data calls {@link #reload()};
 * nothing patches the table's list by hand (U-5).
 */
public final class MainView {

    private final BorderPane root = new BorderPane();

    private final ExpenseService expenses;
    private final BudgetService budgets;
    private final SummaryService summaries;
    private final ImportService imports;
    // One stream each: a table load supersedes table loads, never the summary's.
    private final BackgroundRunner.Latest tableLoads;
    private final BackgroundRunner.Latest summaryLoads;

    // Everything reload() touches is built before the panel whose changes trigger it.
    private final ExpenseTableView tableView = new ExpenseTableView();
    private final SummaryPane summaryPane = new SummaryPane();
    private final FilterPanel filterPanel = new FilterPanel(this::reload);

    public MainView(ExpenseService expenses, BudgetService budgets,
                    SummaryService summaries, ImportService imports,
                    BackgroundRunner runner,
                    Path databasePath, int schemaVersion) {
        this.expenses = Objects.requireNonNull(expenses, "expenses");
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.imports = Objects.requireNonNull(imports, "imports");
        Objects.requireNonNull(runner, "runner");
        this.tableLoads = runner.latest();
        this.summaryLoads = runner.latest();

        root.setLeft(filterPanel.getRoot());
        root.setCenter(tableView.getRoot());
        root.setRight(summaryPane.getRoot());
        root.setBottom(new StatusBar(databasePath, schemaVersion).getRoot());

        // The first load is explicit: FilterPanel sets its initial values before it
        // attaches listeners, so constructing it does not call reload().
        reload();
    }

    /**
     * {@code Parent}, not {@code BorderPane}: the caller needs something to put in a
     * {@code Scene}, not a handle to rearrange the window with.
     */
    public Parent getRoot() {
        return root;
    }

    /** Package-private, for tests that drive the filters. */
    FilterPanel filterPanel() {
        return filterPanel;
    }

    /**
     * The one refresh. Two queries, each on its own latest-only stream, so a burst of filter changes
     * shows only the last month in both places. They are separate tasks, so for a few
     * milliseconds the table and the panel can show different months; one task returning
     * a pair would close that gap and is not worth it here.
     */
    private void reload() {
        // Evaluated here, on the FX thread, before submitting. The lambdas capture the
        // resulting ExpenseFilter, not the controls: reading a ComboBox inside the work
        // would touch the UI from the background thread.
        ExpenseFilter filter = filterPanel.currentFilter();

        tableLoads.run(() -> expenses.find(filter), tableView::setRows, this::showError);
        summaryLoads.run(() -> summaries.summarise(filter.month()), summaryPane::update, this::showError);
    }

    private void showError(Throwable error) {
        // TODO(sprint-18): replace with ErrorDialogs - a readable sentence, not a trace
        error.printStackTrace();
    }
}
