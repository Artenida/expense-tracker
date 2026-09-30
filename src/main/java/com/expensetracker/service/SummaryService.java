package com.expensetracker.service;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.CategorySpend;
import com.expensetracker.domain.CategoryTotal;
import com.expensetracker.domain.Expense;
import com.expensetracker.domain.ExpenseFilter;
import com.expensetracker.domain.Money;
import com.expensetracker.domain.MonthSummary;
import com.expensetracker.store.BudgetStore;
import com.expensetracker.store.ExpenseStore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The month's summary, computed twice: once by asking the database to group, once by
 * loading the rows and grouping in Java. Both are kept and tested for equality - the
 * point is the comparison, not a winner.
 *
 * <p>The only difference between the two is how they get spending per category, the
 * count and the largest expense. Everything after that - the union with the budgets,
 * the sort, the average - is shared, so a bug there cannot pass for a difference
 * between approaches.
 */
public final class SummaryService {

    /**
     * {@code reversed()} applies to everything before it, so the name tiebreak stays
     * ascending. Matches {@code ORDER BY total DESC, category ASC}.
     */
    private static final Comparator<CategoryTotal> BY_SPEND_THEN_NAME =
            Comparator.comparing(CategoryTotal::spent).reversed()
                    .thenComparing(ct -> ct.category().name());

    /**
     * With {@code max}, the same row as sprint 09's
     * {@code ORDER BY amount_cents DESC, spent_on DESC, id DESC LIMIT 1}. All three keys
     * are needed: two 50.00 expenses in a month are ordinary, and with the amount alone
     * {@code max} picks whichever the stream saw first.
     */
    private static final Comparator<Expense> BY_AMOUNT_THEN_DATE_THEN_ID =
            Comparator.comparing(Expense::amount)
                    .thenComparing(Expense::date)
                    .thenComparing(Expense::id);

    private final ExpenseStore expenses;
    private final BudgetStore budgets;

    public SummaryService(ExpenseStore expenses, BudgetStore budgets) {
        this.expenses = Objects.requireNonNull(expenses, "expenses");
        this.budgets = Objects.requireNonNull(budgets, "budgets");
    }

    /** The implementation the application uses. Swapping it is a one-line experiment. */
    public MonthSummary summarise(YearMonth month) {
        return viaSql(month);
    }

    /**
     * Aggregation done by the database: three queries whatever the month holds - the
     * grouped totals, the budgets, and the single largest expense.
     */
    public MonthSummary viaSql(YearMonth month) {
        ExpenseFilter filter = ExpenseFilter.of(month);

        List<CategorySpend> spends = expenses.totalsByCategory(filter.from(), filter.to());
        Map<Category, Long> limits = budgets.limitsByCategory();

        Map<Category, Long> spentByCategory = spends.stream()
                .collect(Collectors.toMap(CategorySpend::category, CategorySpend::spentCents));

        long totalCents = spends.stream().mapToLong(CategorySpend::spentCents).sum();
        int entryCount = spends.stream().mapToInt(CategorySpend::entryCount).sum();

        List<Expense> top = expenses.findTop(filter.from(), filter.to(), 1);

        return assemble(month, totalCents, entryCount, limits,
                buildTotals(spentByCategory, limits),
                top.isEmpty() ? Optional.empty() : Optional.of(top.get(0)));
    }

    /** Aggregation done in Java over every row of the month. */
    public MonthSummary viaStream(YearMonth month) {
        ExpenseFilter filter = ExpenseFilter.of(month);

        List<Expense> rows = expenses.find(filter);
        Map<Category, Long> limits = budgets.limitsByCategory();

        // A HashMap: no order. buildTotals sorts, so this does not have to.
        Map<Category, Long> spentByCategory = rows.stream()
                .collect(Collectors.groupingBy(
                        Expense::category,
                        Collectors.summingLong(e -> Money.toCents(e.amount()))));

        long totalCents = rows.stream().mapToLong(e -> Money.toCents(e.amount())).sum();

        // The list is already in memory; its size is the count.
        return assemble(month, totalCents, rows.size(), limits,
                buildTotals(spentByCategory, limits),
                rows.stream().max(BY_AMOUNT_THEN_DATE_THEN_ID));
    }

    /**
     * The union of the two key sets, sprint 05's rule: an untouched budget still gets a
     * row, spending with no budget gets {@code NO_BUDGET}, a category with neither is
     * left out.
     */
    private static List<CategoryTotal> buildTotals(Map<Category, Long> spentByCategory,
                                                   Map<Category, Long> limitsByCategory) {
        Set<Category> relevant = new LinkedHashSet<>();
        relevant.addAll(spentByCategory.keySet());
        relevant.addAll(limitsByCategory.keySet());

        return relevant.stream()
                .map(category -> CategoryTotal.of(
                        category,
                        spentByCategory.getOrDefault(category, 0L),
                        limitsByCategory.containsKey(category)
                                ? OptionalLong.of(limitsByCategory.get(category))
                                : OptionalLong.empty()))
                .sorted(BY_SPEND_THEN_NAME)
                .toList();
    }

    /**
     * {@code totalBudgeted} comes from every configured limit, not only the categories
     * in {@code totals}; both implementations read it from the same map, so they cannot
     * disagree. The average is guarded because {@code divide} by zero throws, and
     * rounded once, for display - nothing branches on it.
     */
    private static MonthSummary assemble(YearMonth month, long totalCents, int entryCount,
                                         Map<Category, Long> limits,
                                         List<CategoryTotal> totals, Optional<Expense> largest) {
        long budgetedCents = limits.values().stream().mapToLong(Long::longValue).sum();

        BigDecimal average = entryCount == 0
                ? BigDecimal.ZERO
                : Money.fromCents(totalCents).divide(BigDecimal.valueOf(entryCount),
                        2, RoundingMode.HALF_UP);

        return new MonthSummary(month, Money.fromCents(totalCents),
                Money.fromCents(budgetedCents), entryCount, average, totals, largest);
    }
}
