package com.expensetracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Trivial-looking, and they catch a real failure: a file in the wrong directory that
 * Maven never copies, which otherwise shows up as a NullPointerException at startup
 * with nothing pointing at the cause.
 */
class ResourcesTest {

    @Test
    void appCssIsOnTheClasspath() {
        assertNotNull(getClass().getResource("/app.css"), "app.css must be in src/main/resources");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/db/V1__create_tables.sql", "/db/V2__add_indexes.sql"})
    void migrationsAreOnTheClasspath(String resource) {
        assertNotNull(getClass().getResource(resource), resource + " must be in src/main/resources/db");
    }
}
