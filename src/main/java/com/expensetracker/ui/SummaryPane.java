package com.expensetracker.ui;

import com.expensetracker.domain.BudgetStatus;
import com.expensetracker.domain.CategoryTotal;
import com.expensetracker.domain.Money;
import com.expensetracker.domain.MonthSummary;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;

/**
 * Formats a {@code MonthSummary} and does nothing else. Every number shown comes off the
 * summary or its {@code CategoryTotal}s - no arithmetic here (U-6). The sums, the
 * percentages and the status were decided and tested in the domain and sprint 11.
 */
public final class SummaryPane {

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy");

    private final VBox root = new VBox(10);

    public SummaryPane() {
        root.setPadding(new Insets(16));
        root.setPrefWidth(300);
        root.getStyleClass().addAll("summary-panel", "summary-pane");
    }

    /** Rebuilt from scratch: simpler than diffing a dozen rows, and no style class can accumulate. */
    public void update(MonthSummary summary) {
        root.getChildren().setAll(
                header(summary),
                new Separator(),
                categoryRows(summary),
                new Separator(),
                footer(summary));
    }

    public Node getRoot() {
        return root;
    }

    private Node header(MonthSummary s) {
        Label title = new Label(s.yearMonth().format(MONTH_FORMAT));
        title.getStyleClass().add("summary-title");

        // Money.format, not String.format("%.2f"): one formatting point, so two numbers cannot disagree.
        Label totals = new Label("spent " + Money.format(s.totalSpent())
                + " of " + Money.format(s.totalBudgeted()) + " budgeted");
        totals.getStyleClass().add("summary-totals");

        return new VBox(4, title, totals);
    }

    private Node categoryRows(MonthSummary s) {
        VBox rows = new VBox(10);
        s.totals().forEach(total -> rows.getChildren().add(categoryRow(total)));
        return rows;
    }

    private Node categoryRow(CategoryTotal total) {
        Label name = new Label(total.category().displayName());
        name.getStyleClass().add("category-name");

        Label amounts = new Label(Money.format(total.spent()) + " / "
                + total.limit().map(Money::format).orElse("—"));

        // progress() clamps at 1.0, so an exceeded budget is a full bar, never an over-full one.
        ProgressBar bar = new ProgressBar(total.progress());
        bar.setMaxWidth(Double.MAX_VALUE);
        // setAll, not add: add appends, and a reused bar would collect every status it ever had.
        bar.getStyleClass().setAll("progress-bar", total.status().cssClass());

        Label note = new Label(total.status() == BudgetStatus.NO_BUDGET
                ? total.status().label()
                : total.percentUsed().toPlainString() + "%  " + total.status().label());
        note.getStyleClass().add("category-note");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox line = new HBox(8, name, spacer, amounts);
        return new VBox(2, line, bar, note);
    }

    private Node footer(MonthSummary s) {
        VBox box = new VBox(2);
        box.getChildren().add(new Label("Entries: " + s.entryCount()));
        box.getChildren().add(new Label("Average: " + Money.format(s.average())));

        // An empty month has no largest expense, so it simply has no line.
        s.largest().ifPresent(e -> box.getChildren().add(
                new Label("Largest: " + Money.format(e.amount()) + " — " + e.description())));

        box.getStyleClass().add("summary-footer");
        return box;
    }
}
