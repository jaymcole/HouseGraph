package io.github.jaymcole.housegraph.headless;

import java.util.concurrent.CountDownLatch;

/**
 * What holds a headless run open, and the one thing that ends it.
 *
 * <p>Nothing else in a running graph keeps the JVM alive: {@code NodeGraph}'s run executor is
 * virtual-thread-per-task and {@code NodeTimer}'s scheduler thread is a daemon, so a graph that is
 * merely waiting for its next tick has no live non-daemon thread of its own. Without this the
 * process would load a graph, resume it, and exit before it ever fired.
 *
 * <p>A latch rather than a poll loop: a thread parked on a latch costs nothing, wakes on the
 * release itself rather than at the next poll, and cannot spin a core on a machine that is supposed
 * to be idle between events.
 */
final class ShutdownSignal {

    private final CountDownLatch released = new CountDownLatch(1);

    /**
     * Blocks until {@link #release()} is called.
     *
     * <p>An interrupt returns rather than throwing, with the flag restored: the caller's next move
     * is to shut down either way, and it is the same move for both reasons.
     */
    void await() {
        try {
            released.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Releases {@link #await()}. Idempotent, and safe to call from a shutdown hook. */
    void release() {
        released.countDown();
    }
}
