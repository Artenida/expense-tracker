package com.expensetracker.service;

import com.expensetracker.domain.Budget;
import com.expensetracker.domain.Category;
import com.expensetracker.domain.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetServiceTest {

    private FakeBudgetStore fake;
    private BudgetService service;

    @BeforeEach
    void setUp() {
        fake = new FakeBudgetStore();
        service = new BudgetService(fake);
    }

    @Test
    void setLimitStoresTheBudget() {
        Budget set = service.setLimit(Category.GROCERIES, new BigDecimal("400.00"));

        assertEquals(set, service.findByCategory(Category.GROCERIES).orElseThrow());
    }

    /** Spec test 7, at the service level. */
    @Test
    void setLimitTwiceKeepsOneBudget() {
        service.setLimit(Category.GROCERIES, new BigDecimal("400.00"));
        service.setLimit(Category.GROCERIES, new BigDecimal("450.00"));

        List<Budget> all = service.findAll();
        assertEquals(1, all.size());
        assertEquals(0, new BigDecimal("450.00").compareTo(all.get(0).monthlyLimit()));
    }

    @Test
    void setLimitRejectsZeroAndStoresNothing() {
        assertThrows(ValidationException.class, () -> service.setLimit(Category.OTHER, new BigDecimal("0.00")));

        assertTrue(fake.isEmpty());
    }

    @Test
    void setLimitRejectsNegativeAndStoresNothing() {
        assertThrows(ValidationException.class, () -> service.setLimit(Category.OTHER, new BigDecimal("-1.00")));

        assertTrue(fake.isEmpty());
    }

    /** The NO_BUDGET path sprint 11 relies on. */
    @Test
    void findByCategoryIsEmptyWhenUnset() {
        assertTrue(service.findByCategory(Category.LEISURE).isEmpty());
    }

    /** A fresh application has no budgets, and that is not an error. */
    @Test
    void findAllIsEmptyNotNull() {
        assertTrue(service.findAll().isEmpty());
    }
}
