package com.expensetracker.ui;

import com.expensetracker.App;
import com.expensetracker.ui.model.ExpenseRow;
import javafx.scene.Node;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.testfx.api.FxAssert.verifyThat;
import static org.testfx.matcher.base.NodeMatchers.isDisabled;
import static org.testfx.matcher.base.NodeMatchers.isVisible;

/**
 * Three smoke tests, and no more: everything interesting is proven by the fast tests,
 * and a large UI suite breaks on every layout change. These check the pieces are wired
 * together, through the real window.
 *
 * <p>Against a {@code @TempDir} database via the {@code expenses.db} property from
 * sprint 06, so they cannot touch real data.
 */
@Tag("ui")
class SmokeTest extends ApplicationTest {

    @TempDir
    static Path tempDir;

    private App app;

    @BeforeAll
    static void pointAtATemporaryDatabase() {
        System.setProperty("expenses.db", tempDir.resolve("smoke.db").toString());
    }

    @AfterAll
    static void clearIt() {
        System.clearProperty("expenses.db");
    }

    /** The launcher would call init() before start(); without it there is no database. */
    @Override
    public void start(Stage stage) {
        app = new App();
        app.init();
        app.start(stage);
    }

    /** Shuts the app's background worker down, as closing the window would. */
    @Override
    public void stop() {
        app.stop();
    }

    /** A dialog left open by one test would sit over the next test's window. */
    @AfterEach
    void closeDialogs() {
        interact(() -> List.copyOf(Window.getWindows()).stream()
                .filter(w -> w instanceof Stage s && s.getOwner() != null)
                .forEach(Window::hide));
    }

    @Test
    void theWindowOpensAgainstAFreshDatabase() {
        verifyThat("#expenseTable", isVisible());
        verifyThat(".status-bar", isVisible());
    }

    @Test
    void addingAnExpenseThroughTheDialogMakesARowAppear() throws Exception {
        clickOn("Add expense");
        clickOn("#amountField").write("24.90");
        clickOn("#descriptionField").write("smoke test");
        clickOn("OK");

        // The save and the reload run on the worker; wait for the row, not for a fixed time.
        TableView<ExpenseRow> table = lookup("#expenseTable").queryTableView();
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> table.getItems().stream()
                .anyMatch(row -> row.descriptionProperty().get().equals("smoke test")));
    }

    @Test
    void anInvalidAmountKeepsTheDialogOpenWithSaveDisabled() {
        clickOn("Add expense");
        clickOn("#amountField").write("abc");

        verifyThat("OK", isDisabled());
        verifyThat(".dialog-pane", (Node pane) -> pane.isVisible());    // still open
    }
}
