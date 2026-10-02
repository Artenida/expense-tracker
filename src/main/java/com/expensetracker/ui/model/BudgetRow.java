package com.expensetracker.ui.model;

import com.expensetracker.domain.Budget;
import com.expensetracker.domain.Category;
import com.expensetracker.domain.Money;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.Objects;
import java.util.Optional;

/**
 * One row per Category, budget or not. An empty limit means unset - not 0.00, which
 * would mean "a budget of nothing".
 */
public final class BudgetRow {

    private final Category category;
    private final ReadOnlyStringWrapper name;
    // Writable, unlike ExpenseRow's: TextFieldTableCell edits through it.
    private final StringProperty limit;

    public BudgetRow(Category category, Optional<Budget> budget) {
        this.category = Objects.requireNonNull(category, "category");
        this.name = new ReadOnlyStringWrapper(category.displayName());
        this.limit = new SimpleStringProperty(
                budget.map(b -> Money.format(b.monthlyLimit())).orElse(""));
    }

    public Category category() {
        return category;
    }

    public ReadOnlyStringProperty nameProperty() {
        return name.getReadOnlyProperty();
    }

    public StringProperty limitProperty() {
        return limit;
    }
}
