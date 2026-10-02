package com.expensetracker.ui;

import javafx.application.Platform;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.concurrent.TimeoutException;

/**
 * Starts the JavaFX toolkit once per JVM, for tests whose callbacks run on the FX
 * thread. Tests that use it are tagged {@code ui} and need a display session.
 *
 * <p>Goes through TestFX rather than {@code Platform.startup}: the smoke tests start the
 * toolkit through TestFX, and a second, independent startup in the same JVM throws.
 * {@code registerPrimaryStage} is safe to call any number of times.
 */
public final class FxToolkit implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) throws TimeoutException {
        org.testfx.api.FxToolkit.registerPrimaryStage();
        // There are no windows, so without this the toolkit would exit after the first class.
        Platform.setImplicitExit(false);
    }
}
