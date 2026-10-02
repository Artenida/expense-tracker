package com.expensetracker;

/**
 * Entry point. Deliberately does NOT extend Application - see sprint 20.
 * A main class that extends Application makes JavaFX check for its own modules
 * on the module path and refuse to start ("JavaFX runtime components are missing")
 * when they are on the classpath, which is where Maven and jpackage put them.
 *
 * <p>It looks like a class that can safely be deleted. It cannot.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        App.main(args);
    }
}
