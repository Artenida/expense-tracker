package com.expensetracker.ui;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.Expense;
import com.expensetracker.domain.Money;
import com.expensetracker.domain.Validation;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Add and edit, one dialog. Validation happens twice, deliberately (U-2): the domain
 * throws, and this dialog makes sure the user never reaches the throw. It implements no
 * rule itself - every check is a call to {@code Validation}, the same methods the
 * {@code Expense} constructor calls - so the two cannot disagree.
 *
 * <p>A domain exception surfacing from here is a bug in the dialog, not a normal path.
 */
public final class ExpenseDialog {

    private final Dialog<Expense> dialog = new Dialog<>();

    // Package-private so the ui tests can type into them, as a user would.
    final TextField amountField = new TextField();
    final ComboBox<Category> categoryBox = new ComboBox<>();
    final TextField descriptionField = new TextField();
    final DatePicker datePicker = new DatePicker();

    private final Label amountError = errorLabel();
    private final Label descriptionError = errorLabel();
    private final Label dateError = errorLabel();

    private final Expense existing;    // null when adding

    public static Optional<Expense> forNew(Window owner) {
        return new ExpenseDialog(owner, null).dialog.showAndWait();
    }

    public static Optional<Expense> forEditing(Window owner, Expense existing) {
        return new ExpenseDialog(owner, Objects.requireNonNull(existing, "existing")).dialog.showAndWait();
    }

    /** Package-private for tests; callers use the two factories. */
    ExpenseDialog(Window owner, Expense existing) {
        this.existing = existing;
        if (owner != null) {
            dialog.initOwner(owner);    // modal to the main window, and centred on it
        }
        dialog.setTitle(existing == null ? "Add expense" : "Edit expense");

        // Button types before wireValidation(): lookupButton returns null until they exist.
        buildLayout();
        if (existing != null) {
            prefill(existing);
        }
        // After the pre-fill, so a valid existing expense starts with Save enabled.
        wireValidation();

        dialog.setResultConverter(button -> button == ButtonType.OK
                ? result(this.existing, amountField.getText(), categoryBox.getValue(),
                         descriptionField.getText(), datePicker.getValue())
                : null);    // null is how showAndWait knows to return Optional.empty()
    }

    /**
     * The converter's one decision, separated so it can be tested without a toolkit:
     * {@code edit} keeps the id and createdAt. {@code create} here would mint a new id,
     * and the save would fail with ExpenseNotFoundException for an expense on screen.
     */
    static Expense result(Expense existing, String amountText, Category category,
                          String description, LocalDate date) {
        BigDecimal amount = Money.parse(amountText);
        return existing == null
                ? Expense.create(amount, category, description, date)
                : existing.edit(amount, category, description, date);
    }

    /** Package-private for tests. */
    boolean saveDisabled() {
        return dialog.getDialogPane().lookupButton(ButtonType.OK).isDisabled();
    }

    private void buildLayout() {
        // No null entry, unlike the filter: "All categories" means nothing for a purchase.
        categoryBox.getItems().setAll(Category.values());
        categoryBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Category value) {
                return value == null ? "" : value.displayName();
            }

            @Override
            public Category fromString(String text) {
                return null;
            }
        });
        categoryBox.setValue(Category.OTHER);
        categoryBox.setMaxWidth(Double.MAX_VALUE);

        amountField.setPromptText("24.90");
        descriptionField.setPromptText("Weekly shop");
        datePicker.setValue(LocalDate.now());
        // Prevents clicking a future day. Validation.date still reports one and the domain
        // still rejects it: the picker prevents, the validator reports, the domain enforces.
        datePicker.setDayCellFactory(picker -> new DateCell() {
            @Override
            public void updateItem(LocalDate date, boolean empty) {
                // First, always: cells are reused as the user changes month.
                super.updateItem(date, empty);
                setDisable(empty || date.isAfter(LocalDate.now()));
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        grid.setPadding(new Insets(16));

        // Each error under its field, in column 1. No row for the category: a ComboBox
        // without a null entry cannot hold an invalid value.
        grid.addRow(0, new Label("Amount"), amountField);
        grid.add(amountError, 1, 1);
        grid.addRow(2, new Label("Category"), categoryBox);
        grid.addRow(3, new Label("Description"), descriptionField);
        grid.add(descriptionError, 1, 4);
        grid.addRow(5, new Label("Date"), datePicker);
        grid.add(dateError, 1, 6);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    }

    /** Money.format, so the field shows 24.90 and re-parsing gives the same value. */
    private void prefill(Expense e) {
        amountField.setText(Money.format(e.amount()));
        categoryBox.setValue(e.category());
        descriptionField.setText(e.description());
        datePicker.setValue(e.date());
    }

    private void wireValidation() {
        bindError(amountField.textProperty(), amountError, Validation::amount);
        bindError(descriptionField.textProperty(), descriptionError, Validation::description);
        bindError(datePicker.valueProperty(), dateError, Validation::date);

        // Every property hasErrors() reads must be listed: a missing one means Save stops
        // re-enabling when the user fixes that field, and nothing reports it.
        BooleanBinding invalid = Bindings.createBooleanBinding(
                this::hasErrors,
                amountField.textProperty(),
                descriptionField.textProperty(),
                datePicker.valueProperty(),
                categoryBox.valueProperty());

        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(invalid);
    }

    private boolean hasErrors() {
        return !Validation.amount(amountField.getText()).isEmpty()
                || !Validation.description(descriptionField.getText()).isEmpty()
                || !Validation.date(datePicker.getValue()).isEmpty()
                || categoryBox.getValue() == null;
    }

    /**
     * Messages appear only once the user changes a field: an empty Add dialog opens with
     * Save disabled but no red text. Edit pre-fills before this runs, so it opens clean too.
     */
    private static <T> void bindError(ObservableValue<T> value, Label label,
                                      Function<T, List<String>> validator) {
        value.addListener((obs, old, now) -> show(label, validator.apply(now)));
    }

    /** The first message only: it is always the most specific, and a list is noise next to a field. */
    private static void show(Label label, List<String> errors) {
        label.setText(errors.isEmpty() ? "" : errors.get(0));
        label.setVisible(!errors.isEmpty());
        // managed as well as visible: a hidden but managed label keeps its line, and the
        // dialog jumps by that much as messages come and go.
        label.setManaged(!errors.isEmpty());
    }

    private static Label errorLabel() {
        Label label = new Label();
        label.getStyleClass().add("field-error");
        label.setVisible(false);
        label.setManaged(false);
        return label;
    }

    /** Package-private for tests: the message under a field, or empty while it is hidden. */
    Optional<String> amountMessage() {
        return visibleMessage(amountError);
    }

    Optional<String> descriptionMessage() {
        return visibleMessage(descriptionError);
    }

    private static Optional<String> visibleMessage(Label label) {
        return label.isVisible() ? Optional.of(label.getText()) : Optional.empty();
    }
}
