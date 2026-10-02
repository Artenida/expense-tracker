package com.expensetracker.ui.task;

import javafx.concurrent.Task;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The one place service calls leave the FX Application Thread. The work runs on the
 * worker; both callbacks come back on the FX thread, because {@code Task} fires its
 * handlers there.
 *
 * <p>One worker, not a pool: every store call opens its own SQLite connection, and two
 * threads writing the same file produce intermittent {@code SQLITE_BUSY}. One thread
 * serialises database access and keeps task order predictable.
 */
public final class BackgroundRunner {

    // Daemon so a missed shutdown cannot keep the JVM alive; named so stack traces say whose it is.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "expense-worker");
        t.setDaemon(true);
        return t;
    });

    public <T> void run(Supplier<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        Task<T> task = task(work);
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> onError.accept(task.getException()));
        executor.submit(task);
    }

    /**
     * A new stream of requests where only the newest outcome is delivered. Each caller
     * that reloads one thing - the table, the summary - takes its own: with a single
     * shared counter, the summary's request would supersede the table's and the table
     * would never load.
     */
    public Latest latest() {
        return new Latest();
    }

    /** For a Task built elsewhere - sprint 19's import, which needs progress and cancel. */
    public void submit(Task<?> task) {
        executor.submit(task);
    }

    /** Lets running work finish for up to two seconds, then interrupts it. Safe to call twice. */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            // Catching cleared the flag; restore it so callers further up still see it.
            Thread.currentThread().interrupt();
        }
    }

    /** Package-private for the shutdown tests. */
    boolean isShutdown() {
        return executor.isShutdown();
    }

    /**
     * Requests on one stream supersede each other; requests on different streams do not.
     * The stale work still runs to completion - this discards the result, it does not
     * cancel - and with one worker it delays the newer request. Acceptable for a read.
     */
    public final class Latest {

        // Incremented on the FX thread and compared there too; atomic so the question never arises.
        private final AtomicLong latestRequest = new AtomicLong();

        private Latest() {
        }

        public <T> void run(Supplier<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
            long request = latestRequest.incrementAndGet();

            Task<T> task = task(work);
            task.setOnSucceeded(e -> {
                if (request == latestRequest.get()) {
                    onSuccess.accept(task.getValue());
                }
            });
            // Guarded too: a stale failure would otherwise report an error for a request
            // the user has already moved on from.
            task.setOnFailed(e -> {
                if (request == latestRequest.get()) {
                    onError.accept(task.getException());
                }
            });
            executor.submit(task);
        }
    }

    /**
     * Task catches whatever escapes call() and routes it to setOnFailed, so a failing
     * task never kills the worker thread.
     */
    private static <T> Task<T> task(Supplier<T> work) {
        return new Task<>() {
            @Override
            protected T call() {
                return work.get();
            }
        };
    }
}
