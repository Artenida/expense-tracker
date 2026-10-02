package com.expensetracker.ui;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.ExpenseFilter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.YearMonth;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Constructing a ComboBox needs the toolkit, so these are tagged ui. */
@Tag("ui")
@ExtendWith(FxToolkit.class)
class FilterPanelTest {

    @Test
    void startsOnThisMonthAndAllCategories() {
        ExpenseFilter filter = new FilterPanel(() -> { }).currentFilter();

        assertEquals(YearMonth.now(), filter.month());
        assertEquals(Optional.empty(), filter.category());
    }

    @Test
    void currentFilterWithoutACategory() {
        FilterPanel panel = new FilterPanel(() -> { });

        panel.select(YearMonth.of(2026, 9), null);

        assertEquals(ExpenseFilter.of(YearMonth.of(2026, 9)), panel.currentFilter());
    }

    @Test
    void currentFilterWithACategory() {
        FilterPanel panel = new FilterPanel(() -> { });

        panel.select(YearMonth.of(2026, 9), Category.GROCERIES);

        assertEquals(ExpenseFilter.of(YearMonth.of(2026, 9), Category.GROCERIES), panel.currentFilter());
    }

    /** The initial setValue calls run before the listeners exist, so the owner's field is never read half-built. */
    @Test
    void constructionDoesNotFireOnChange() {
        AtomicInteger changes = new AtomicInteger();

        new FilterPanel(changes::incrementAndGet);

        assertEquals(0, changes.get());
    }

    @Test
    void eachSelectionFiresOnChange() {
        AtomicInteger changes = new AtomicInteger();
        FilterPanel panel = new FilterPanel(changes::incrementAndGet);

        panel.select(YearMonth.now().minusMonths(1), Category.HEALTH);

        assertEquals(2, changes.get(), "one for the month, one for the category");
    }
}
