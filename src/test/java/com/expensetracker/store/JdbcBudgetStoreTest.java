package com.expensetracker.store;

import com.expensetracker.domain.Budget;
import com.expensetracker.domain.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcBudgetStoreTest {

    @TempDir
    Path tempDir;

    private Database db;
    private BudgetStore budgets;

    @BeforeEach
    void setUp() {
        db = new Database(tempDir.resolve("test.db"));
        new SchemaMigrator(db).migrate();
        budgets = new JdbcBudgetStore(db);
    }

    // --- spec test 7 --------------------------------------------------------

    /** Both assertions matter: the value alone passes if a second row came back first. */
    @Test
    void settingABudgetTwiceLeavesOneRowWithTheNewerValue() {
        budgets.upsert(Budget.of(Category.GROCERIES, new BigDecimal("400.00")));
        budgets.upsert(Budget.of(Category.GROCERIES, new BigDecimal("450.00")));

        List<Budget> all = budgets.findAll();
        assertEquals(1, all.size());
        assertEquals(0, new BigDecimal("450.00").compareTo(all.get(0).monthlyLimit()));
    }

    // --- upsert -------------------------------------------------------------

    @Test
    void upsertInsertsWhenAbsent() {
        budgets.upsert(Budget.of(Category.HEALTH, new BigDecimal("100.00")));

        assertEquals(10_000, budgets.findByCategory(Category.HEALTH).orElseThrow().limitCents());
    }

    /** Explicit timestamps: two Instant.now() calls in a row can, rarely, be equal. */
    @Test
    void upsertUpdatesUpdatedAt() {
        Instant first = Instant.parse("2026-09-01T10:00:00Z");
        Instant second = Instant.parse("2026-09-20T10:00:00Z");

        budgets.upsert(Budget.restore(Category.HEALTH, new BigDecimal("100.00"), first));
        budgets.upsert(Budget.restore(Category.HEALTH, new BigDecimal("120.00"), second));

        assertEquals(second, budgets.findByCategory(Category.HEALTH).orElseThrow().updatedAt());
    }

    @Test
    void budgetsForDifferentCategoriesCoexist() {
        budgets.upsert(Budget.of(Category.GROCERIES, new BigDecimal("400.00")));
        budgets.upsert(Budget.of(Category.TRANSPORT, new BigDecimal("120.00")));

        assertEquals(2, budgets.findAll().size());
    }

    // --- reading ------------------------------------------------------------

    @Test
    void findByCategoryReturnsEmptyWhenUnset() {
        assertTrue(budgets.findByCategory(Category.LEISURE).isEmpty());
    }

    /** Alphabetical by stored name - not the enum's declaration order (GROCERIES first). */
    @Test
    void findAllIsOrderedByCategory() {
        budgets.upsert(Budget.of(Category.TRANSPORT, new BigDecimal("1.00")));
        budgets.upsert(Budget.of(Category.GROCERIES, new BigDecimal("1.00")));
        budgets.upsert(Budget.of(Category.EDUCATION, new BigDecimal("1.00")));

        List<Category> order = budgets.findAll().stream().map(Budget::category).toList();

        assertEquals(List.of(Category.EDUCATION, Category.GROCERIES, Category.TRANSPORT), order);
    }

    @Test
    void findAllReturnsAnImmutableList() {
        List<Budget> all = budgets.findAll();

        assertThrows(UnsupportedOperationException.class, () -> all.add(null));
    }

    /** The default method works through the interface - the field is a BudgetStore. */
    @Test
    void limitsByCategoryMapsEveryBudget() {
        budgets.upsert(Budget.of(Category.GROCERIES, new BigDecimal("400.00")));
        budgets.upsert(Budget.of(Category.TRANSPORT, new BigDecimal("120.50")));

        assertEquals(Map.of(Category.GROCERIES, 40_000L, Category.TRANSPORT, 12_050L),
                budgets.limitsByCategory());
    }

    // --- the database's own line of defence ---------------------------------

    /** The domain already rejects zero; this proves the CHECK is real, not decoration. */
    @Test
    void zeroLimitIsRejectedByTheDatabase() throws SQLException {
        try (Connection c = db.open();
             Statement s = c.createStatement()) {
            SQLException thrown = assertThrows(SQLException.class, () -> s.executeUpdate(
                    "INSERT INTO budgets (category, limit_cents, updated_at) "
                            + "VALUES ('OTHER', 0, '2026-09-01T00:00:00Z')"));

            assertTrue(thrown.getMessage().contains("CHECK"), thrown::getMessage);
        }
    }
}
