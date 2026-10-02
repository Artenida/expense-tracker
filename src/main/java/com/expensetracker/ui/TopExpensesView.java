package com.expensetracker.ui;

import com.expensetracker.service.ExpenseService;
import com.expensetracker.ui.model.ExpenseRow;
import com.expensetracker.ui.task.BackgroundRunner;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

import java.time.YearMonth;
import java.util.Objects;

/**
 * The N largest expenses of the month. The ordering and the LIMIT are the database's
 * (sprint 09's SQL) - not the loaded month sorted in Java, which would work, be faster
 * on a small month, and miss the point.
 */
public final class TopExpensesView {

    private final TableView<ExpenseRow> table = new TableView<>();
    private final ObservableList<ExpenseRow> rows = FXCollections.observableArrayList();
    private final Spinner<Integer> count = new Spinner<>(1, 50, 5);
    private final BorderPane root = new BorderPane();

    private final ExpenseService expenses;
    private final BackgroundRunner.Latest loads;

    private YearMonth month = YearMonth.now();

    public TopExpensesView(ExpenseService expenses, BackgroundRunner runner) {
        this.expenses = Objects.requireNonNull(expenses, "expenses");
        this.loads = runner.latest();

        ExpenseTableView.addColumns(table);
        // Unsorted, the SortedList keeps the database's order: largest first.
        ExpenseTableView.buildSorting(table, rows);
        table.setPlaceholder(new Label("No expenses this month"));

        count.valueProperty().addListener((obs, old, now) -> reload(month));

        HBox controls = new HBox(8, new Label("Show the largest"), count);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(8));
        root.setTop(controls);
        root.setCenter(table);
    }

    /** The month is remembered, so changing the spinner reloads the same month. */
    public void reload(YearMonth month) {
        this.month = Objects.requireNonNull(month, "month");
        int limit = count.getValue();       // read here, on the FX thread
        loads.run(() -> expenses.topExpenses(month, limit),
                found -> rows.setAll(ExpenseRow.wrap(found)),
                ErrorDialogs::show);
    }

    public Node getRoot() {
        return root;
    }
}
