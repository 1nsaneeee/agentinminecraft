package dev.mcagent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** The world boundary is substituted so even a failing test cannot edit a save. */
public final class GameBridge {
    private static final ConcurrentLinkedQueue<Runnable> DEFERRED_TASKS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SAY_CALLS = new AtomicInteger();
    private static volatile boolean deferServerTasks;
    private static volatile CountDownLatch deferredQueued = new CountDownLatch(0);

    public static boolean running() { return false; }

    public static <T> T onServer(java.util.function.Supplier<T> task, long timeoutMs) {
        if (!deferServerTasks) return task.get();
        CompletableFuture<T> result = new CompletableFuture<>();
        DEFERRED_TASKS.add(() -> {
            try {
                result.complete(task.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        deferredQueued.countDown();
        try {
            return result.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            throw new IllegalStateException("deferred server task timed out", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the deferred server task", error);
        } catch (java.util.concurrent.ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(String.valueOf(cause), cause);
        }
    }

    public static com.google.gson.JsonObject snapshot() { return Json.obj(); }
    public static void say(String text) { SAY_CALLS.incrementAndGet(); }
    public static void sayComponent(com.google.gson.JsonObject json) {}
    public static String runCommand(String command, String player, int permission) { return "executed"; }

    static synchronized void resetLifecycleHarness() {
        DEFERRED_TASKS.clear();
        SAY_CALLS.set(0);
        deferServerTasks = false;
        deferredQueued = new CountDownLatch(0);
    }

    static synchronized void deferServerTasks() {
        DEFERRED_TASKS.clear();
        deferServerTasks = true;
        deferredQueued = new CountDownLatch(1);
    }

    static boolean awaitDeferredTask(long timeout, TimeUnit unit) throws InterruptedException {
        return deferredQueued.await(timeout, unit);
    }

    static int drainDeferredTasks() {
        deferServerTasks = false;
        int drained = 0;
        Runnable task;
        while ((task = DEFERRED_TASKS.poll()) != null) {
            task.run();
            drained++;
        }
        return drained;
    }

    static int sayCalls() {
        return SAY_CALLS.get();
    }
}
