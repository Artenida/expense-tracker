package com.expensetracker.ui;

import com.expensetracker.domain.Expense;
import com.expensetracker.domain.ExpenseFilter;
import com.expensetracker.service.BudgetService;
import com.expensetracker.service.ExpenseService;
import com.expensetracker.service.ImportService;
import com.expensetracker.service.SummaryService;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;

import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * The window's layout: filters left, table centre, summary right, status bar bottom.
 * The centre takes whatever width is left over - that is {@code BorderPane}'s policy,
 * not something configured here.
 *
 * <p>Holds services, never stores. Sprints 15 to 19 replace each remaining placeholder.
 */
public final class MainView {

    private static final double FILTER_WIDTH = 200;
    private static final double SUMMARY_WIDTH = 300;

    private final BorderPane root = new BorderPane();
    private final ExpenseTableView tableView = new ExpenseTableView();

    private final ExpenseService expenses;
    private final BudgetService budgets;
    private final SummaryService summaries;
    private final ImportService imports;

    public MainView(ExpenseService expenses, BudgetService budgets,
                    SummaryService summaries, ImportService imports,
                    Path databasePath, int schemaVersion) {
        this.expenses = Objects.requireNonNull(expenses, "expenses");
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.imports = Objects.requireNonNull(imports, "imports");

        root.setLeft(placeholder("Filters", "filter-panel", FILTER_WIDTH));
        root.setCenter(tableView.getRoot());
        root.setRight(placeholder("Summary", "summary-panel", SUMMARY_WIDTH));
        root.setBottom(new StatusBar(databasePath, schemaVersion).getRoot());

        reloadTemporarily();
    }

    /**
     * {@code Parent}, not {@code BorderPane}: the caller needs something to put in a
     * {@code Scene}, not a handle to rearrange the window with.
     */
    public Parent getRoot() {
        return root;
    }

    /**
     * Last month, so there is data to look at without the current month's
     * "not in the future" rule getting in the way. Sprint 16 replaces this with a
     * {@code reload()} driven by the filter controls.
     */
    // TODO(sprint-15): this blocks the FX Application Thread. Move to BackgroundRunner.
    private void reloadTemporarily() {
        ExpenseFilter filter = ExpenseFilter.of(YearMonth.now().minusMonths(1));
        List<Expense> found = expenses.find(filter);
        tableView.setRows(found);
    }

    /** Style classes rather than {@code setStyle}: styling lives in app.css. */
    private static Node placeholder(String text, String styleClass, double prefWidth) {
        Label label = new Label(text);
        label.getStyleClass().add("placeholder");

        StackPane pane = new StackPane(label);
        pane.setPadding(new Insets(16));
        if (styleClass != null) {
            pane.getStyleClass().add(styleClass);
        }
        if (prefWidth > 0) {
            pane.setPrefWidth(prefWidth);
        }
        return pane;
    }
}
