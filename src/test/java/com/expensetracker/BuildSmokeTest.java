package com.expensetracker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildSmokeTest {

    @Test
    void theBuildRunsTests() {
        assertEquals(21, Runtime.version().feature());
    }
}
