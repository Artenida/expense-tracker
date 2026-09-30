package com.expensetracker.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The type the whole application exists to move around - and so it knows nothing about
 * any layer that moves it.
 *
 * <p>Immutable, and impossible to hold in an invalid state: every instance passes
 * through the one private constructor, which validates before assigning anything.
 * Downstream code gets to assume its input is sane.
 */
public final class Expense {

    private final String id;
    private final BigDecimal amount;
    private final Category category;
    private final String description;
    private final LocalDate date;
    private final Instant createdAt;

    /**
     * The gate. Collects every problem before throwing, so sprint 17's dialog can light
     * up three fields at once instead of revealing them one attempt at a time.
     */
    private Expense(String id, BigDecimal amount, Category category,
                    String description, LocalDate date, Instant createdAt) {
        List<String> errors = new ArrayList<>();
        errors.addAll(Validation.amount(amount == null ? null : amount.toPlainString()));
        errors.addAll(Validation.description(description));
        errors.addAll(Validation.date(date));
        if (category == null) {
            errors.add("category is required");
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        this.id = Objects.requireNonNull(id, "id");
        // UNNECESSARY: normalise to scale 2, and throw rather than round if that would
        // lose a digit. Validation already refused three decimals, so a throw is a bug.
        this.amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        this.category = category;
        this.description = description.trim();
        this.date = date;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** A new expense: invents the id and stamps the moment it was made. */
    public static Expense create(BigDecimal amount, Category category,
                                 String description, LocalDate date) {
        return new Expense(UUID.randomUUID().toString(), amount, category,
                description, date, Instant.now());
    }

    /**
     * An expense that already exists - read back by the store in sprint 07, where the
     * id and timestamp are given rather than invented.
     *
     * <p>Decision (reading check 2): {@code restore} runs the full validation,
     * including "not in the future". A stored row that fails it was edited by hand or
     * written by a bug, and the mapper is the place to find out - not three layers up
     * when a total is wrong. The cost is that a clock set backwards makes such a row
     * unloadable; that is loud and fixable, whereas a silently accepted bad row is not.
     */
    public static Expense restore(String id, BigDecimal amount, Category category,
                                  String description, LocalDate date, Instant createdAt) {
        return new Expense(id, amount, category, description, date, createdAt);
    }

    /**
     * Keeps identity ({@code id}) and history ({@code createdAt}); replaces everything
     * the user can change. Goes through the same constructor, so it re-validates.
     */
    public Expense edit(BigDecimal amount, Category category,
                        String description, LocalDate date) {
        return new Expense(this.id, amount, category, description, date, this.createdAt);
    }

    public String id() {
        return id;
    }

    public BigDecimal amount() {
        return amount;
    }

    public Category category() {
        return category;
    }

    public String description() {
        return description;
    }

    public LocalDate date() {
        return date;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Expense other)) {
            return false;
        }
        return id.equals(other.id)
                && amount.compareTo(other.amount) == 0   // compareTo, not equals: scale
                && category == other.category
                && description.equals(other.description)
                && date.equals(other.date)
                && createdAt.equals(other.createdAt);
    }

    /**
     * Hashes cents rather than the {@code BigDecimal}, whose hash includes the scale.
     * Whatever {@code equals} treats as equal must hash equal, or a {@code HashSet}
     * loses things.
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, Money.toCents(amount), category, description, date, createdAt);
    }

    @Override
    public String toString() {
        return "Expense[" + id + " " + Money.format(amount) + " " + category + " on " + date + "]";
    }
}
