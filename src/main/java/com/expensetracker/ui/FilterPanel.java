package com.expensetracker.ui;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.ExpenseFilter;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Month and category. Knows nothing about what a change causes: it calls {@code onChange},
 * which keeps it from reaching into the table or the summary.
 */
public final class FilterPanel {

    private static final int MONTHS_BACK = 24;
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy");

    private final ComboBox<YearMonth> month = new ComboBox<>();
    private final ComboBox<Category> category = new ComboBox<>();
    private final VBox root = new VBox(12);

    public FilterPanel(Runnable onChange) {
        Objects.requireNonNull(onChange, "onChange");
        buildMonth();
        buildCategory();

        // Listeners last: the initial setValue calls above must not fire onChange while
        // the owner is still constructing this panel - its field would still be null.
        // The owner triggers the first load itself, once everything exists.
        month.valueProperty().addListener((obs, old, now) -> onChange.run());
        category.valueProperty().addListener((obs, old, now) -> onChange.run());

        root.setPadding(new Insets(16));
        root.setPrefWidth(200);
        root.getStyleClass().add("filter-panel");
        // A ComboBox sizes to its content unless told it may stretch.
        month.setMaxWidth(Double.MAX_VALUE);
        category.setMaxWidth(Double.MAX_VALUE);
        root.getChildren().addAll(new Label("Month"), month, new Label("Category"), category);
    }

    /** The one place the "All" null becomes {@code Optional.empty()}. */
    public ExpenseFilter currentFilter() {
        YearMonth selected = month.getValue();
        Category chosen = category.getValue();      // null means "All"
        return chosen == null ? ExpenseFilter.of(selected) : ExpenseFilter.of(selected, chosen);
    }

    public Node getRoot() {
        return root;
    }

    /** Package-private, for tests: what the user would do by picking from the combos. */
    void select(YearMonth selectedMonth, Category selectedCategory) {
        month.setValue(selectedMonth);
        category.setValue(selectedCategory);
    }

    /** Newest first, and no future months: Expense rejects future dates, so they could only be empty. */
    private void buildMonth() {
        YearMonth now = YearMonth.now();
        for (int i = 0; i < MONTHS_BACK; i++) {
            month.getItems().add(now.minusMonths(i));
        }
        month.setValue(now);

        month.setConverter(new StringConverter<>() {
            @Override
            public String toString(YearMonth value) {
                // Asked about the empty selection too, so null must be handled.
                return value == null ? "" : value.format(MONTH_FORMAT);
            }

            @Override
            public YearMonth fromString(String text) {
                return null;    // only called for an editable combo, and this one is not
            }
        });
    }

    private void buildCategory() {
        // null is the "All" entry. This is the one deliberate null in the project:
        // it lives in this control and this converter, and currentFilter() turns it
        // back into Optional.empty() before anything else sees it. An ALL constant on
        // Category would leak into the dialog, the summary and the CSV.
        category.getItems().add(null);
        category.getItems().addAll(Category.values());
        category.setValue(null);

        category.setConverter(new StringConverter<>() {
            @Override
            public String toString(Category value) {
                return value == null ? "All categories" : value.displayName();
            }

            @Override
            public Category fromString(String text) {
                return null;
            }
        });
    }
}
