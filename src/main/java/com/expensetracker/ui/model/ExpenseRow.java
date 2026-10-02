package com.expensetracker.ui.model;

import com.expensetracker.domain.Expense;
import com.expensetracker.domain.Money;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Adapter between an {@code Expense}, which knows nothing about JavaFX, and the table,
 * which wants observable values. The properties live here so they never reach the domain.
 *
 * <p>Read-only because the {@code Expense} is immutable: an edit produces a new
 * {@code Expense}, and a reload produces a new row. Nothing is updated in place.
 */
public final class ExpenseRow {

    private final Expense source;

    private final ReadOnlyStringWrapper date;
    private final ReadOnlyStringWrapper category;
    private final ReadOnlyStringWrapper description;
    private final ReadOnlyStringWrapper amount;
    private final ReadOnlyObjectWrapper<BigDecimal> amountValue;

    public ExpenseRow(Expense source) {
        this.source = Objects.requireNonNull(source, "source");
        // ISO by definition; display name, not enum name; Money.format is the one rounding point.
        this.date = new ReadOnlyStringWrapper(source.date().toString());
        this.category = new ReadOnlyStringWrapper(source.category().displayName());
        this.description = new ReadOnlyStringWrapper(source.description());
        this.amount = new ReadOnlyStringWrapper(Money.format(source.amount()));
        this.amountValue = new ReadOnlyObjectWrapper<>(source.amount());
    }

    /** The original object, not a copy: sprint 17's dialog edits it to keep id and createdAt. */
    public Expense source() {
        return source;
    }

    public ReadOnlyStringProperty dateProperty() {
        return date.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty categoryProperty() {
        return category.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty descriptionProperty() {
        return description.getReadOnlyProperty();
    }

    /** The formatted amount, for anything that wants plain text. Sorts as text - "100.00" before "24.90". */
    public ReadOnlyStringProperty amountProperty() {
        return amount.getReadOnlyProperty();
    }

    /** The value itself, for the table column: BigDecimal is Comparable, so it sorts numerically. */
    public ReadOnlyObjectProperty<BigDecimal> amountValueProperty() {
        return amountValue.getReadOnlyProperty();
    }

    /** Keeps the store's order: the table opens newest-first only if this does not reorder. */
    public static List<ExpenseRow> wrap(List<Expense> expenses) {
        return expenses.stream().map(ExpenseRow::new).toList();
    }
}
