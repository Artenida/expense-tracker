package com.expensetracker.ui.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The executor alone, with no work submitted, so no toolkit is needed and these run in
 * every build. Anything that runs a Task needs the toolkit - Task posts its state change
 * to the FX thread before call() starts - so "shutdown waits for running work" lives in
 * BackgroundRunnerTest, tagged ui.
 */
class BackgroundRunnerShutdownTest {

    @Test
    void shutdownStopsTheExecutor() {
        BackgroundRunner runner = new BackgroundRunner();

        runner.shutdown();

        assertTrue(runner.isShutdown());
    }

    @Test
    void shutdownIsIdempotent() {
        BackgroundRunner runner = new BackgroundRunner();
        runner.shutdown();

        assertDoesNotThrow(runner::shutdown);
    }
}
