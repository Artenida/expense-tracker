package com.expensetracker;

import com.expensetracker.io.CsvReader;
import com.expensetracker.io.CsvWriter;
import com.expensetracker.service.BudgetService;
import com.expensetracker.service.ExpenseService;
import com.expensetracker.service.ImportService;
import com.expensetracker.service.SummaryService;
import com.expensetracker.store.BudgetStore;
import com.expensetracker.store.Database;
import com.expensetracker.store.ExpenseStore;
import com.expensetracker.store.JdbcBudgetStore;
import com.expensetracker.store.JdbcExpenseStore;
import com.expensetracker.store.SchemaMigrator;
import com.expensetracker.store.StoreException;
import com.expensetracker.ui.MainView;
import com.expensetracker.ui.task.BackgroundRunner;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.util.Objects;

/**
 * The only class that knows every layer: it builds the stores, hands them to the
 * services, and hands the services to the view. Nothing else calls a constructor from
 * another package. If this starts holding logic, that logic belongs in a service.
 */
public class App extends Application {

    private Database database;
    private SchemaMigrator migrator;
    private ExpenseService expenseService;
    private BudgetService budgetService;
    private SummaryService summaryService;
    private ImportService importService;

    /** Created in start, not init: it belongs to the UI, and init may never reach start. */
    private BackgroundRunner runner;

    /** Set in {@code init()}, read in {@code start()}: init runs before there is a UI to show it in. */
    private StoreException startupFailure;

    public static void main(String[] args) {
        launch(args);
    }

    /**
     * On the launcher thread, before the toolkit shows anything - so migrations finish
     * before the window appears, and a slow first run does not stall the FX thread.
     * Must not touch the UI.
     */
    @Override
    public void init() {
        try {
            database = Database.resolveDefault();
            migrator = new SchemaMigrator(database);
            migrator.migrate();

            ExpenseStore expenses = new JdbcExpenseStore(database);
            BudgetStore budgets = new JdbcBudgetStore(database);

            expenseService = new ExpenseService(expenses);
            budgetService = new BudgetService(budgets);
            summaryService = new SummaryService(expenses, budgets);
            importService = new ImportService(expenses, new CsvReader(), new CsvWriter());
        } catch (StoreException e) {
            startupFailure = e;
        }
    }

    @Override
    public void start(Stage stage) {
        // A half-migrated database that reaches an interactive UI is much worse than
        // one that refuses to start.
        if (startupFailure != null) {
            showFatalError(startupFailure);
            Platform.exit();
            return;
        }

        runner = new BackgroundRunner();
        MainView view = new MainView(expenseService, budgetService,
                summaryService, importService, runner,
                database.path(), migrator.currentVersion());

        Scene scene = new Scene(view.getRoot(), 1100, 700);
        // getResource returns null for a missing file, and a null stylesheet fails later
        // with a message that does not mention CSS.
        scene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("/app.css"), "app.css not on the classpath")
                        .toExternalForm());

        stage.setTitle("Expense Tracker");
        stage.setScene(scene);
        // Below this the three regions overlap into nonsense.
        stage.setMinWidth(900);
        stage.setMinHeight(560);
        stage.show();
    }

    @Override
    public void stop() {
        // Called even when start() bailed out on a startup failure, before the runner existed.
        if (runner != null) {
            runner.shutdown();
        }
    }

    /** The store's message names the file; the cause carries SQLite's reason. */
    private static void showFatalError(StoreException e) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Expense Tracker");
        alert.setHeaderText("The expense database could not be opened");
        String reason = e.getCause() == null ? "" : "\n\n" + e.getCause().getMessage();
        alert.setContentText(e.getMessage() + reason);
        alert.showAndWait();
    }
}
