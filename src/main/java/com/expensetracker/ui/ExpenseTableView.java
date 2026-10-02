package com.expensetracker.ui;

import com.expensetracker.domain.Expense;
import com.expensetracker.domain.Money;
import com.expensetracker.ui.model.ExpenseRow;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;

import java.math.BigDecimal;
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
    private final Consumer<ExpenseRow> onDelete;
    private final Runnable onImport;
    private final Runnable onExport;

    /** What the buttons and rows do is the owner's business; this class only reports the gesture. */
    public ExpenseTableView(Runnable onAdd, Consumer<ExpenseRow> onEdit, Consumer<ExpenseRow> onDelete,
                            Runnable onImport, Runnable onExport) {
        this.onAdd = Objects.requireNonNull(onAdd, "onAdd");
        this.onEdit = Objects.requireNonNull(onEdit, "onEdit");
        this.onDelete = Objects.requireNonNull(onDelete, "onDelete");
        this.onImport = Objects.requireNonNull(onImport, "onImport");
        this.onExport = Objects.requireNonNull(onExport, "onExport");
        table.setId("expenseTable");
        addColumns(table);
        table.setPlaceholder(new Label("No expenses for this selection"));
        buildSorting(table, rows);
        buildRowFactory();
        buildDeleteKey();
        root.setTop(buildToolbar());
        root.setCenter(table);
    }

    /** One setAll, one change notification: clear-then-add would flicker. */
    public void setRows(List<Expense> expenses) {
        rows.setAll(ExpenseRow.wrap(expenses));
    }

    public Node getRoot() {
        return root;
    }

    /** Shared with TopExpensesView, so the two tables cannot drift apart. */
    static void addColumns(TableView<ExpenseRow> table) {
        TableColumn<ExpenseRow, String> date = new TableColumn<>("Date");
        date.setCellValueFactory(c -> c.getValue().dateProperty());
        date.setPrefWidth(110);

        TableColumn<ExpenseRow, String> category = new TableColumn<>("Category");
        category.setCellValueFactory(c -> c.getValue().categoryProperty());
        category.setPrefWidth(120);

        TableColumn<ExpenseRow, String> description = new TableColumn<>("Description");
        description.setCellValueFactory(c -> c.getValue().descriptionProperty());
        description.setPrefWidth(320);

        // Holds the value, displays the format: a String column can only ever sort as text,
        // which put "100.00" before "24.90".
        TableColumn<ExpenseRow, BigDecimal> amount = new TableColumn<>("Amount");
        amount.setCellValueFactory(c -> c.getValue().amountValueProperty());
        amount.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : Money.format(value));
            }
        });
        amount.setPrefWidth(100);
        amount.getStyleClass().add("amount-column");

        table.getColumns().setAll(List.of(date, category, description, amount));
        // Fill the width instead of leaving a grey gap on the right.
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    /**
     * The table is the source of the comparator and the list follows it; the table's
     * side is read-only, so the reverse does not compile. Forgetting the bind, or copying
     * the comparator once with setComparator, does compile - and clicking a header does nothing.
     */
    static void buildSorting(TableView<ExpenseRow> table, ObservableList<ExpenseRow> rows) {
        SortedList<ExpenseRow> sorted = new SortedList<>(rows);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
    }

    /**
     * Double-click to edit, right-click for Edit and Delete. TableRows exist for the blank
     * area below the last row too, with a null item - hence isEmpty() on the click, and no
     * context menu at all on an empty row.
     */
    private void buildRowFactory() {
        table.setRowFactory(view -> {
            TableRow<ExpenseRow> row = new TableRow<>();

            MenuItem edit = new MenuItem("Edit…");
            MenuItem delete = new MenuItem("Delete");
            edit.setOnAction(e -> onEdit.accept(row.getItem()));
            delete.setOnAction(e -> onDelete.accept(row.getItem()));

            ContextMenu menu = new ContextMenu(edit, delete);
            // The cast picks the then(...) overload; a bare null is ambiguous.
            row.contextMenuProperty().bind(
                    Bindings.when(row.emptyProperty()).then((ContextMenu) null).otherwise(menu));

            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    onEdit.accept(row.getItem());
                }
            });
            return row;
        });
    }

    /** BACK_SPACE too: on a Mac laptop, the key labelled "delete" sends it. */
    private void buildDeleteKey() {
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                ExpenseRow selected = table.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    onDelete.accept(selected);
                }
            }
        });
    }

    private Node buildToolbar() {
        Button add = new Button("Add expense");
        Button impor = new Button("Import CSV");
        Button export = new Button("Export CSV");

        add.setOnAction(e -> onAdd.run());
        impor.setOnAction(e -> onImport.run());
        export.setOnAction(e -> onExport.run());

        ToolBar bar = new ToolBar(add, impor, export);
        bar.getStyleClass().add("expense-toolbar");
        return bar;
    }
}
