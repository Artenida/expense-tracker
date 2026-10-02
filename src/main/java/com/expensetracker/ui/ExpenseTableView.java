package com.expensetracker.ui;

import com.expensetracker.domain.Expense;
import com.expensetracker.ui.model.ExpenseRow;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The expense table and its toolbar. Data goes into {@code rows}; the table shows a
 * {@code SortedList} view of it, so a reload keeps whatever sort the user chose.
 */
public final class ExpenseTableView {

    private final TableView<ExpenseRow> table = new TableView<>();
    private final ObservableList<ExpenseRow> rows = FXCollections.observableArrayList();
    private final BorderPane root = new BorderPane();

    private final Runnable onAdd;
    private final Consumer<ExpenseRow> onEdit;

    /** What the buttons and rows do is the owner's business; this class only reports the gesture. */
    public ExpenseTableView(Runnable onAdd, Consumer<ExpenseRow> onEdit) {
        this.onAdd = Objects.requireNonNull(onAdd, "onAdd");
        this.onEdit = Objects.requireNonNull(onEdit, "onEdit");
        buildColumns();
        buildSorting();
        buildRowFactory();
        root.setTop(buildToolbar());
        root.setCenter(table);
    }

    /** One {@code setAll}, one change notification: clear-then-add would flicker. */
    public void setRows(List<Expense> expenses) {
        rows.setAll(ExpenseRow.wrap(expenses));
    }

    public Node getRoot() {
        return root;
    }

    private void buildColumns() {
        TableColumn<ExpenseRow, String> date = new TableColumn<>("Date");
        date.setCellValueFactory(c -> c.getValue().dateProperty());
        date.setPrefWidth(110);

        TableColumn<ExpenseRow, String> category = new TableColumn<>("Category");
        category.setCellValueFactory(c -> c.getValue().categoryProperty());
        category.setPrefWidth(120);

        TableColumn<ExpenseRow, String> description = new TableColumn<>("Description");
        description.setCellValueFactory(c -> c.getValue().descriptionProperty());
        description.setPrefWidth(320);

        TableColumn<ExpenseRow, String> amount = new TableColumn<>("Amount");
        amount.setCellValueFactory(c -> c.getValue().amountProperty());
        amount.setPrefWidth(100);
        amount.getStyleClass().add("amount-column");

        table.getColumns().setAll(List.of(date, category, description, amount));
        // Fill the width instead of leaving a grey gap on the right.
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No expenses for this selection"));
    }

    /**
     * The table is the source of the comparator and the list follows it; the table's
     * side is read-only, so the reverse does not compile. Forgetting the bind, or copying
     * the comparator once with setComparator, does compile - and clicking a header does nothing.
     */
    private void buildSorting() {
        SortedList<ExpenseRow> sorted = new SortedList<>(rows);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
    }

    /**
     * Double-click to edit. TableRows exist for the blank area below the last row too,
     * and their item is null - hence the isEmpty() guard.
     */
    private void buildRowFactory() {
        table.setRowFactory(view -> {
            TableRow<ExpenseRow> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    onEdit.accept(row.getItem());
                }
            });
            return row;
        });
    }

    /** Disabled rather than missing: an honest placeholder beats a button that does nothing. */
    private Node buildToolbar() {
        Button add = new Button("Add expense");
        Button impor = new Button("Import CSV");
        Button export = new Button("Export CSV");

        add.setOnAction(e -> onAdd.run());
        impor.setDisable(true);     // sprint 19
        export.setDisable(true);    // sprint 19

        ToolBar bar = new ToolBar(add, impor, export);
        bar.getStyleClass().add("expense-toolbar");
        return bar;
    }
}
