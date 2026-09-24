package com.expensetracker.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public enum Category {
    GROCERIES ("Groceries", 40_000),
    TRANSPORT ("Transport", 12_000),
    HOUSING   ("Housing",   90_000),
    LEISURE   ("Leisure",   15_000),
    HEALTH    ("Health",    10_000),
    EDUCATION ("Education", 20_000),
    OTHER     ("Other",     10_000);

    private final String displayName;
    private final long suggestedLimitCents;

    Category(String displayName, long suggestedLimitCents) {
        this.displayName = displayName;
        this.suggestedLimitCents = suggestedLimitCents;
    }

    public String displayName() {
        return displayName;
    }

    public long suggestedLimitCents() {
        return suggestedLimitCents;
    }

    /**
     * Reads a category from stored or imported text. The message on failure is shown
     * to the user by the CSV importer, so it names the offending value and lists the
     * alternatives rather than reading like a stack trace.
     */
    public static Category parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw unknown(raw);
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw unknown(raw.trim());
        }
    }

    private static IllegalArgumentException unknown(String raw) {
        return new IllegalArgumentException(
                "unknown category '" + raw + "' — expected one of: " + validNames());
    }

    private static String validNames() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
