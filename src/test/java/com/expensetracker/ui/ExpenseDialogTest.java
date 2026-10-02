package com.expensetracker.ui;

import com.expensetracker.domain.Category;
import com.expensetracker.domain.Expense;
import javafx.application.Platform;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Save binding and the error labels, without showing the dialog: a Dialog must be
 * built on the FX thread, but it need not be on screen for its bindings to run.
 */
@Tag("ui")
@ExtendWith(FxToolkit.class)
class ExpenseDialogTest {

    @Test
    void saveStartsDisabledWhenAddingAndNoMessageIsShown() throws Exception {
        onFx(() -> {
            ExpenseDialog d = new ExpenseDialog(null, null);

            assertTrue(d.saveDisabled());
            assertEquals(Optional.empty(), d.amountMessage(), "no red text before the user types");
            assertEquals(LocalDate.now(), d.datePicker.getValue());
            assertEquals(Category.OTHER, d.categoryBox.getValue());
        });
    }

    @Test
    void anAmountAndADescriptionAreEnoughToSave() throws Exception {
        onFx(() -> {
            ExpenseDialog d = new ExpenseDialog(null, null);

            d.amountField.setText("2");
            assertTrue(d.saveDisabled(), "the description is still empty");

            d.descriptionField.setText("Coffee");
            assertFalse(d.saveDisabled());
        });
    }

    @Test
    void clearingAFieldDisablesSaveAndShowsItsOwnMessage() throws Exception {
        onFx(() -> {
            ExpenseDialog d = new ExpenseDialog(null, null);
            d.amountField.setText("2");
            d.descriptionField.setText("Coffee");

            d.amountField.setText("");

            assertTrue(d.saveDisabled());
            assertEquals(Optional.of("amount is required"), d.amountMessage());
            assertEquals(Optional.empty(), d.descriptionMessage(), "the other field stays clean");
        });
    }

    @Test
    void eachRuleGivesItsOwnMessage() throws Exception {
        onFx(() -> {
            ExpenseDialog d = new ExpenseDialog(null, null);

            d.amountField.setText("12,50");
            assertEquals(Optional.of("amount must be a number with at most two decimals, for example 24.90"),
                    d.amountMessage());

            d.amountField.setText("0");
            assertEquals(Optional.of("amount must be greater than zero"), d.amountMessage());

            d.amountField.setText("24.90");
            assertEquals(Optional.empty(), d.amountMessage());
        });
    }

    /** The binding lists the date as a dependency: fixing the date alone re-enables Save. */
    @Test
    void fixingOnlyTheDateReenablesSave() throws Exception {
        onFx(() -> {
            ExpenseDialog d = new ExpenseDialog(null, null);
            d.amountField.setText("2");
            d.descriptionField.setText("Coffee");

            d.datePicker.setValue(LocalDate.now().plusDays(1));
            assertTrue(d.saveDisabled());

            d.datePicker.setValue(LocalDate.now());
            assertFalse(d.saveDisabled());
        });
    }

    @Test
    void editingPrefillsEveryFieldAndStartsEnabled() throws Exception {
        Expense existing = Expense.create(new BigDecimal("24.9"), Category.HEALTH, "Pharmacy",
                LocalDate.of(2026, 9, 10));

        onFx(() -> {
            ExpenseDialog d = new ExpenseDialog(null, existing);

            assertEquals("24.90", d.amountField.getText());
            assertEquals(Category.HEALTH, d.categoryBox.getValue());
            assertEquals("Pharmacy", d.descriptionField.getText());
            assertEquals(LocalDate.of(2026, 9, 10), d.datePicker.getValue());
            assertFalse(d.saveDisabled());
        });
    }

    // --- helpers ------------------------------------------------------------

    private static void onFx(Runnable assertions) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                assertions.run();
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            done.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            // Rethrow the assertion itself, so the report shows its message.
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }
}
