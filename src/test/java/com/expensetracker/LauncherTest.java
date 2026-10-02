package com.expensetracker;

import javafx.application.Application;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Looks absurd - asserting a class does NOT extend something. It is here because the
 * failure it prevents shows up at package time, weeks after someone "tidies up" by
 * merging Launcher into App, and the message carries the reason to whoever hits it.
 */
class LauncherTest {

    @Test
    void launcherDoesNotExtendApplication() {
        assertFalse(Application.class.isAssignableFrom(Launcher.class),
                "Launcher must NOT extend Application, or java -jar and the jpackage bundle fail with "
                        + "'JavaFX runtime components are missing' - see sprint 20");
    }
}
