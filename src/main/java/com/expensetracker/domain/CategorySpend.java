package com.expensetracker.domain;

import java.util.Objects;

/**
 * One row of a {@code GROUP BY category}: what the database adds up for a category.
 *
 * <p>In {@code domain} because the store returns domain objects. Carries cents rather
 * than {@code BigDecimal} because it is on its way to
 * {@link CategoryTotal#of(Category, long, java.util.OptionalLong)}, which takes cents -
 * converting here and back there would be two pointless conversions.
 */
public record CategorySpend(Category category, long spentCents, int entryCount) {

    public CategorySpend {
        Objects.requireNonNull(category, "category");
        if (spentCents < 0) {
            throw new IllegalArgumentException("spentCents < 0: " + spentCents);
        }
        if (entryCount < 0) {
            throw new IllegalArgumentException("entryCount < 0: " + entryCount);
        }
    }
}
