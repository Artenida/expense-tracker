package com.expensetracker.ui;

import com.expensetracker.domain.Budget;
import com.expensetracker.domain.Category;
import com.expensetracker.domain.Money;
import com.expensetracker.domain.Validation;
import com.expensetracker.domain.ValidationException;
import com.expensetracker.service.BudgetService;
import com.expensetracker.ui.model.BudgetRow;
import com.expensetracker.ui.task.BackgroundRunner;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.TextFieldTableCell;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One editable row per Category. Editing needs all three of: an editable table, a column
 * with an editing cell factory, and a writable property - miss one and nothing happens,
 * silently.
 */
public final class BudgetPane {

    private final TableView<BudgetRow> table = new TableView<>();
    private final ObservableList<BudgetRow> rows = FXCollections.observableArrayList();

    private final BudgetService budgets;
    private final BackgroundRunner runner;
    private final BackgroundRunner.Latest loads;
    private final Runnable onChange;

    /** {@code onChange} is MainView::reload: a new budget changes the summary (U-5 across panes). */
    public BudgetPane(BudgetService budgets, BackgroundRunner runner, Runnable onChange) {
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.loads = runner.latest();
        this.onChange = Objects.requireNonNull(onChange, "onChange");

        TableColumn<BudgetRow, String> category = new TableColumn<>("Category");
        category.setCellValueFactory(c -> c.getValue().nameProperty());
        category.setEditable(false);

        TableColumn<BudgetRow, String> limit = new TableColumn<>("Monthly limit");
        limit.setCellValueFactory(c -> c.getValue().limitProperty());
        limit.setCellFactory(TextFieldTableCell.forTableColumn());
        limit.getStyleClass().add("amount-column");
        // Fires on Enter only. Escape or clicking away cancels with no event, so a value
        // reaches the database only on a deliberate commit.
        limit.setOnEditCommit(event -> commit(event.getRowValue(), event.getNewValue()));

        table.getColumns().setAll(List.of(category, limit));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setEditable(true);
        table.setItems(rows);
    }

    /** One query for all budgets, then a row for every category from it - not seven queries. */
    public void reload() {
        loads.run(budgets::findAll, all -> {
            Map<Category, Budget> byCategory = all.stream()
                    .collect(Collectors.toMap(Budget::category, Function.identity()));
            rows.setAll(Arrays.stream(Category.values())
                    .map(c -> new BudgetRow(c, Optional.ofNullable(byCategory.get(c))))
                    .toList());
        }, ErrorDialogs::show);
    }

    public Node getRoot() {
        return table;
    }

    /**
     * Validation.amount again - the rule shared with the expense dialog and the CSV import.
     * Every path that does not save ends in reload(), so the cell never shows a value that
     * is not in the database.
     */
    private void commit(BudgetRow row, String newValue) {
        String raw = newValue == null ? "" : newValue.trim();

        if (raw.isEmpty()) {
            reload();       // clearing is not deleting: put the stored value back
            return;
        }

        List<String> errors = Validation.amount(raw);
        if (!errors.isEmpty()) {
            reload();
            ErrorDialogs.show(new ValidationException(errors));
            return;
        }

        runner.run(() -> budgets.setLimit(row.category(), Money.parse(raw)),
                saved -> {
                    reload();
                    onChange.run();
                },
                error -> {
                    reload();
                    ErrorDialogs.show(error);
                });
    }
}
