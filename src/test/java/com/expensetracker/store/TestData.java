package com.expensetracker.store;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.Expense;
import com.expensetracker.domain.Money;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Generated expenses for tests. In test sources: nothing in the app needs fake data. */
final class TestData {

    private TestData() {
    }

    /**
     * Seeded, so a failure - or a slow run - is reproducible. The month must be in the
     * past: Expense rejects future dates, so a month that is still in progress fails on
     * any random day after today.
     */
    static List<Expense> randomExpenses(int count, YearMonth month, long seed) {
        Random random = new Random(seed);
        List<Expense> out = new ArrayList<>(count);
        Category[] categories = Category.values();
        int days = month.lengthOfMonth();

        for (int i = 0; i < count; i++) {
            out.add(Expense.create(
                    Money.fromCents(random.nextInt(100, 20_000)),
                    categories[random.nextInt(categories.length)],
                    "generated " + i,
                    month.atDay(random.nextInt(1, days + 1))));
        }
        return out;
    }
}
