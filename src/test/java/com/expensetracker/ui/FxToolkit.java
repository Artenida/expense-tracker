package com.expensetracker.ui;

import javafx.application.Platform;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Starts the JavaFX toolkit once per JVM, for tests whose callbacks run on the FX
 * thread. Tests that use it are tagged {@code ui} and need a display session.
 */
public final class FxToolkit implements BeforeAllCallback {

    // Platform.startup may be called once per JVM; a second call throws.
    private static boolean started = false;

    @Override
    public void beforeAll(ExtensionContext context) throws InterruptedException {
        start();
    }

    private static synchronized void start() throws InterruptedException {
        if (started) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("toolkit did not start");
        }
        // There are no windows, so without this the toolkit would exit after the first class.
        Platform.setImplicitExit(false);
        started = true;
    }
}
