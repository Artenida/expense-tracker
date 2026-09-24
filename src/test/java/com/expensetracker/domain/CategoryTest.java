package com.expensetracker.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoryTest {

    @Test
    void parseAcceptsExactName() {
        assertSame(Category.GROCERIES, Category.parse("GROCERIES"));
    }

    @Test
    void parseIsCaseInsensitiveAndTrims() {
        assertSame(Category.GROCERIES, Category.parse("  groceries "));
    }

    @Test
    void parseRejectsUnknownAndListsValidNames() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> Category.parse("FOOD"));

        assertTrue(e.getMessage().contains("FOOD"), "message should name the bad value");
        for (Category c : Category.values()) {
            assertTrue(e.getMessage().contains(c.name()),
                    "message should list " + c.name() + " but was: " + e.getMessage());
        }
    }

    @Test
    void parseRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> Category.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Category.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> Category.parse(null));
    }

    @Test
    void everyCategoryHasAPositiveSuggestedLimit() {
        for (Category c : Category.values()) {
            assertTrue(c.suggestedLimitCents() > 0,
                    c.name() + " has a non-positive suggested limit");
        }
    }

    @Test
    void everyCategoryHasANonBlankDisplayName() {
        for (Category c : Category.values()) {
            assertFalse(c.displayName() == null || c.displayName().isBlank(),
                    c.name() + " has a blank display name");
        }
    }

    @Test
    void thereAreExactlySevenCategories() {
        assertEquals(7, Category.values().length);
    }
}
