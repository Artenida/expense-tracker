package com.expensetracker.ui.task;

import com.expensetracker.store.StoreException;
import com.expensetracker.ui.FxToolkit;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Callbacks fire on the FX thread, so these need a running toolkit and a display.
 * Excluded from {@code mvn clean test}; run with {@code mvn test -DexcludedGroups=}.
 *
 * <p>Waits are futures and latches with a timeout, never sleeps: a passing test takes
 * milliseconds, and a hanging one fails in five seconds.
 */
@Tag("ui")
@ExtendWith(FxToolkit.class)
class BackgroundRunnerTest {

    private static final long TIMEOUT_SECONDS = 5;

    private BackgroundRunner runner;

    @BeforeEach
    void setUp() {
        runner = new BackgroundRunner();
    }

    @AfterEach
    void tearDown() {
        runner.shutdown();
    }

    // --- spec test 13 -------------------------------------------------------

    @Test
    void aTaskThatThrowsInvokesOnFailedAndLeavesTheListUntouched() throws Exception {
        ObservableList<String> rows = FXCollections.observableArrayList("original");
        CompletableFuture<Throwable> failure = new CompletableFuture<>();

        runner.run(
                () -> { throw new StoreException("database is on fire"); },
                value -> rows.setAll("should not happen"),
                failure::complete);

        Throwable caught = failure.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Task wraps nothing: an ExecutionException here would mean the routing is wrong.
        assertInstanceOf(StoreException.class, caught);
        assertEquals("database is on fire", caught.getMessage());
        assertEquals(List.of("original"), new ArrayList<>(rows));
    }

    // --- success and threading ----------------------------------------------

    @Test
    void successDeliversTheValue() throws Exception {
        CompletableFuture<String> delivered = new CompletableFuture<>();

        runner.run(() -> "value", delivered::complete, delivered::completeExceptionally);

        assertEquals("value", delivered.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void workRunsOffTheFxThreadAndCallbacksRunOnIt() throws Exception {
        AtomicBoolean workOnFx = new AtomicBoolean(true);
        AtomicBoolean callbackOnFx = new AtomicBoolean(false);
        CompletableFuture<Void> done = new CompletableFuture<>();

        runner.run(
                () -> { workOnFx.set(Platform.isFxApplicationThread()); return "x"; },
                v -> { callbackOnFx.set(Platform.isFxApplicationThread()); done.complete(null); },
                done::completeExceptionally);

        done.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertFalse(workOnFx.get(), "work must not run on the FX thread");
        assertTrue(callbackOnFx.get(), "callbacks must run on the FX thread");
    }

    @Test
    void errorsRunOnTheFxThread() throws Exception {
        CompletableFuture<Boolean> onFx = new CompletableFuture<>();

        runner.run(
                () -> { throw new StoreException("boom"); },
                v -> onFx.completeExceptionally(new AssertionError("should have failed")),
                e -> onFx.complete(Platform.isFxApplicationThread()));

        assertTrue(onFx.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void errorsDoNotKillTheWorker() throws Exception {
        CompletableFuture<Throwable> first = new CompletableFuture<>();
        CompletableFuture<String> second = new CompletableFuture<>();

        runner.run(() -> { throw new StoreException("first fails"); }, v -> { }, first::complete);
        runner.run(() -> "second runs", second::complete, second::completeExceptionally);

        first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("second runs", second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    // --- latest -------------------------------------------------------------

    @Test
    void onlyTheNewestRequestDeliversItsResult() throws Exception {
        BackgroundRunner.Latest stream = runner.latest();
        List<String> delivered = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch holdFirst = new CountDownLatch(1);
        CountDownLatch newestDone = new CountDownLatch(1);

        // One worker: the slow request is held until the fast one is queued behind it,
        // so the order is fixed by the latch, not by timing.
        stream.run(awaiting(holdFirst, "slow"), delivered::add, e -> { });
        stream.run(() -> "fast", v -> { delivered.add(v); newestDone.countDown(); }, e -> { });

        holdFirst.countDown();
        assertTrue(newestDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertEquals(List.of("fast"), delivered);
    }

    @Test
    void aSingleRequestStillDelivers() throws Exception {
        BackgroundRunner.Latest stream = runner.latest();
        CompletableFuture<String> delivered = new CompletableFuture<>();

        stream.run(() -> "only", delivered::complete, delivered::completeExceptionally);

        assertEquals("only", delivered.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void staleFailuresAreAlsoDiscarded() throws Exception {
        BackgroundRunner.Latest stream = runner.latest();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch holdFirst = new CountDownLatch(1);
        CountDownLatch newestDone = new CountDownLatch(1);

        stream.run(() -> {
            awaitQuietly(holdFirst);
            throw new StoreException("stale failure");
        }, v -> { }, errors::add);
        stream.run(() -> "fresh", v -> newestDone.countDown(), errors::add);

        holdFirst.countDown();
        assertTrue(newestDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertEquals(List.of(), errors);
    }

    @Test
    void separateStreamsDoNotSupersedeEachOther() throws Exception {
        BackgroundRunner.Latest table = runner.latest();
        BackgroundRunner.Latest summary = runner.latest();
        CompletableFuture<String> tableResult = new CompletableFuture<>();
        CompletableFuture<String> summaryResult = new CompletableFuture<>();

        // What MainView.reload() does: one request on each stream, back to back.
        table.run(() -> "rows", tableResult::complete, tableResult::completeExceptionally);
        summary.run(() -> "summary", summaryResult::complete, summaryResult::completeExceptionally);

        assertEquals("rows", tableResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("summary", summaryResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    // --- shutdown -----------------------------------------------------------

    @Test
    void shutdownWaitsForRunningWork() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();

        runner.run(() -> {
            started.countDown();
            sleepQuietly(200);   // the work itself is slow; the test does not sleep
            finished.set(true);
            return null;
        }, v -> { }, e -> { });

        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        runner.shutdown();

        assertTrue(finished.get(), "shutdown returned before the running task finished");
    }

    // --- helpers ------------------------------------------------------------

    private static <T> Supplier<T> awaiting(CountDownLatch latch, T value) {
        return () -> {
            awaitQuietly(latch);
            return value;
        };
    }

    /** Suppliers cannot throw checked exceptions. */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch never opened");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
