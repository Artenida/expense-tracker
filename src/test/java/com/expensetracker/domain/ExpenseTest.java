package com.expensetracker.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpenseTest {

    private static final BigDecimal TEN = new BigDecimal("10.00");
    private static final LocalDate TODAY = LocalDate.now();

    private static Expense coffee() {
        return Expense.create(new BigDecimal("24.90"), Category.LEISURE, "coffee", TODAY);
    }

    // --- construction -------------------------------------------------------

    @Test
    void validExpenseIsCreated() {
        LocalDate yesterday = TODAY.minusDays(1);
        Expense e = Expense.create(new BigDecimal("24.90"), Category.GROCERIES, "milk", yesterday);

        assertEquals(0, new BigDecimal("24.90").compareTo(e.amount()));
        assertEquals(Category.GROCERIES, e.category());
        assertEquals("milk", e.description());
        assertEquals(yesterday, e.date());
    }

    @Test
    void createGeneratesAUniqueId() {
        assertNotEquals(coffee().id(), coffee().id());
    }

    @Test
    void createStampsCreatedAt() {
        Duration age = Duration.between(coffee().createdAt(), Instant.now());

        assertTrue(age.compareTo(Duration.ofSeconds(1)) < 0, () -> "createdAt was " + age + " ago");
    }

    @Test
    void descriptionIsTrimmed() {
        Expense e = Expense.create(TEN, Category.OTHER, "  coffee  ", TODAY);

        assertEquals("coffee", e.description());
    }

    @Test
    void amountIsNormalisedToScaleTwo() {
        Expense e = Expense.create(new BigDecimal("24.9"), Category.OTHER, "coffee", TODAY);

        assertEquals(0, new BigDecimal("24.90").compareTo(e.amount()));
        assertEquals(2, e.amount().scale());
    }

    // --- rejection: spec test 3 and test 14 ---------------------------------

    /** Asserts the message, not just the type - the wrong rule firing would also throw. */
    @Test
    void tomorrowIsRejected() {
        ValidationException thrown = assertThrows(ValidationException.class,
                () -> Expense.create(TEN, Category.OTHER, "future lunch", TODAY.plusDays(1)));

        assertTrue(thrown.errors().stream().anyMatch(m -> m.contains("future")),
                () -> "errors were " + thrown.errors());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-5.00", "24.905"})
    void badAmountsAreRejected(String amount) {
        ValidationException thrown = assertThrows(ValidationException.class,
                () -> Expense.create(new BigDecimal(amount), Category.OTHER, "coffee", TODAY));

        assertTrue(thrown.errors().stream().allMatch(m -> m.startsWith("amount")),
                () -> "errors were " + thrown.errors());
    }

    @Test
    void blankDescriptionIsRejected() {
        ValidationException thrown = assertThrows(ValidationException.class,
                () -> Expense.create(TEN, Category.OTHER, "", TODAY));

        assertEquals(List.of("description is required"), thrown.errors());
    }

    @Test
    void longDescriptionIsRejected() {
        String tooLong = "x".repeat(101);

        ValidationException thrown = assertThrows(ValidationException.class,
                () -> Expense.create(TEN, Category.OTHER, tooLong, TODAY));

        assertTrue(thrown.errors().get(0).contains("was 101"), () -> "errors were " + thrown.errors());
    }

    @Test
    void nullCategoryIsRejected() {
        ValidationException thrown = assertThrows(ValidationException.class,
                () -> Expense.create(TEN, null, "coffee", TODAY));

        assertEquals(List.of("category is required"), thrown.errors());
    }

    /** What lets sprint 17's dialog light up three fields at once. */
    @Test
    void allProblemsAreReportedAtOnce() {
        ValidationException thrown = assertThrows(ValidationException.class,
                () -> Expense.create(new BigDecimal("-1"), Category.OTHER, "", TODAY.plusDays(1)));

        assertEquals(3, thrown.errors().size(), () -> "errors were " + thrown.errors());
    }

    // --- the equality contract ----------------------------------------------

    /** The HashSet line is the one that catches a broken contract in practice. */
    @Test
    void scaleDoesNotAffectEqualityOrHash() {
        Instant now = Instant.now();
        Expense a = Expense.restore("id-1", new BigDecimal("24.9"), Category.OTHER,
                "coffee", TODAY, now);
        Expense b = Expense.restore("id-1", new BigDecimal("24.90"), Category.OTHER,
                "coffee", TODAY, now);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(1, new HashSet<>(List.of(a, b)).size());
    }

    @Test
    void differentIdsAreNotEqual() {
        Instant now = Instant.now();
        Expense a = Expense.restore("id-1", TEN, Category.OTHER, "coffee", TODAY, now);
        Expense b = Expense.restore("id-2", TEN, Category.OTHER, "coffee", TODAY, now);

        assertNotEquals(a, b);
    }

    @Test
    @SuppressWarnings({"EqualsWithItself", "ConstantValue"})
    void equalsIsReflexiveAndNullSafe() {
        Expense a = coffee();

        assertTrue(a.equals(a));
        assertTrue(!a.equals(null));
    }

    @Test
    @SuppressWarnings("EqualsBetweenInconvertibleTypes")
    void equalsRejectsOtherTypes() {
        assertTrue(!coffee().equals("coffee"));
    }

    // --- edit ---------------------------------------------------------------

    @Test
    void editKeepsIdAndCreatedAt() {
        Expense original = coffee();
        LocalDate yesterday = TODAY.minusDays(1);

        Expense edited = original.edit(TEN, Category.HEALTH, "aspirin", yesterday);

        assertEquals(original.id(), edited.id());
        assertEquals(original.createdAt(), edited.createdAt());
        assertEquals(0, TEN.compareTo(edited.amount()));
        assertEquals(Category.HEALTH, edited.category());
        assertEquals("aspirin", edited.description());
        assertEquals(yesterday, edited.date());
    }

    @Test
    void editRevalidates() {
        Expense original = coffee();

        assertThrows(ValidationException.class,
                () -> original.edit(TEN, Category.OTHER, "   ", TODAY));
    }

    /** The immutability claim, actually tested. */
    @Test
    void editDoesNotMutateTheOriginal() {
        Expense original = coffee();

        original.edit(TEN, Category.HEALTH, "aspirin", TODAY.minusDays(1));

        assertEquals(0, new BigDecimal("24.90").compareTo(original.amount()));
        assertEquals(Category.LEISURE, original.category());
        assertEquals("coffee", original.description());
        assertEquals(TODAY, original.date());
    }

    @Test
    void toStringNamesIdAndAmount() {
        Expense e = coffee();

        assertTrue(e.toString().contains(e.id()));
        assertTrue(e.toString().contains("24.90"));
    }
}
