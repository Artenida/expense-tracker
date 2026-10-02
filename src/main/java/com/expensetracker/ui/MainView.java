package com.expensetracker.ui;

import com.expensetracker.domain.Expense;
import com.expensetracker.domain.ExpenseFilter;
import com.expensetracker.domain.Money;
import com.expensetracker.service.BudgetService;
import com.expensetracker.service.ExpenseService;
import com.expensetracker.service.ImportService;
import com.expensetracker.service.SummaryService;
import com.expensetracker.ui.model.ExpenseRow;
import com.expensetracker.ui.task.BackgroundRunner;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The window's layout: filters left, tabs centre, summary right, status bar bottom.
 * The centre takes whatever width is left over - that is {@code BorderPane}'s policy,
 * not something configured here.
 *
 * <p>Holds services, never stores. Everything that changes data calls {@link #reload()};
 * nothing patches a table's list by hand (U-5).
 */
public final class MainView {

    private final BorderPane root = new BorderPane();

    private final ExpenseService expenses;
    private final BudgetService budgets;
    private final SummaryService summaries;
    private final ImportService imports;

    private final BackgroundRunner runner;
    // One stream each: a table load supersedes table loads, never the summary's.
    private final BackgroundRunner.Latest tableLoads;
    private final BackgroundRunner.Latest summaryLoads;

    // Everything reload() touches is built before the panel whose changes trigger it.
    private final ExpenseTableView tableView = new ExpenseTableView(this::onAdd, this::onEdit, this::onDelete);
    private final SummaryPane summaryPane = new SummaryPane();
    private final TopExpensesView topExpensesView;
    private final BudgetPane budgetPane;
    private final FilterPanel filterPanel = new FilterPanel(this::reload);

    public MainView(ExpenseService expenses, BudgetService budgets,
                    SummaryService summaries, ImportService imports,
                    BackgroundRunner runner,
                    Path databasePath, int schemaVersion) {
        this.expenses = Objects.requireNonNull(expenses, "expenses");
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.imports = Objects.requireNonNull(imports, "imports");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.tableLoads = runner.latest();
        this.summaryLoads = runner.latest();
        this.topExpensesView = new TopExpensesView(expenses, runner);
        this.budgetPane = new BudgetPane(budgets, runner, this::reload);

        // UNAVAILABLE: the default lets a user close a tab with no way to bring it back.
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("Expenses", tableView.getRoot()),
                new Tab("Top expenses", topExpensesView.getRoot()),
                new Tab("Budgets", budgetPane.getRoot()));

        root.setLeft(filterPanel.getRoot());
        root.setCenter(tabs);
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
     * The one refresh: the table, the summary, the top list and the budgets. Each on its
     * own latest-only stream, so a burst of filter changes shows only the last month. They
     * are separate tasks on one worker, so for a few milliseconds the views can show
     * different months; measured on a local SQLite file it is imperceptible.
     */
    private void reload() {
        // Evaluated here, on the FX thread, before submitting. The lambdas capture the
        // resulting ExpenseFilter, not the controls: reading a ComboBox inside the work
        // would touch the UI from the background thread.
        ExpenseFilter filter = filterPanel.currentFilter();

        tableLoads.run(() -> expenses.find(filter), tableView::setRows, ErrorDialogs::show);
        summaryLoads.run(() -> summaries.summarise(filter.month()), summaryPane::update, ErrorDialogs::show);
        topExpensesView.reload(filter.month());
        budgetPane.reload();
    }

    /**
     * The dialog runs on the FX thread - showAndWait runs a nested event loop, so the
     * window keeps drawing - and the save goes through the runner. On success, reload():
     * never rows.add(...) (U-5).
     */
    private void onAdd() {
        ExpenseDialog.forNew(window()).ifPresent(expense ->
                runner.run(() -> expenses.add(expense.amount(), expense.category(),
                                expense.description(), expense.date()),
                        added -> reload(),
                        ErrorDialogs::show));
    }

    /** row.source() is the real Expense, so edit() keeps its id and createdAt. */
    private void onEdit(ExpenseRow row) {
        ExpenseDialog.forEditing(window(), row.source()).ifPresent(edited ->
                runner.run(() -> expenses.update(edited), updated -> reload(), this::reloadAndShow));
    }

    /**
     * Names what it will destroy: "Are you sure?" is a speed bump people click through,
     * while the amount, description and date let them notice they picked the wrong row.
     */
    private void onDelete(ExpenseRow row) {
        Expense expense = row.source();

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(window());
        confirm.setTitle("Delete expense");
        confirm.setHeaderText("Delete this expense?");
        confirm.setContentText(Money.format(expense.amount()) + " — " + expense.description()
                + "\non " + expense.date());

        // Empty for Cancel and for dismissal alike: one check covers both.
        if (confirm.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            return;
        }

        runner.run(() -> {
            expenses.delete(expense.id());
            return null;
        }, ignored -> reload(), this::reloadAndShow);
    }

    /**
     * Refresh first, then report. After ExpenseNotFoundException the table is showing a row
     * the database does not have; reloading is what makes "the list has been refreshed" true.
     */
    private void reloadAndShow(Throwable error) {
        reload();
        ErrorDialogs.show(error);
    }

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }
}
