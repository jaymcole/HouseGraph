package io.github.jaymcole.housegraph.sdk;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A repeating clock for a node, with no toolkit behind it — the replacement for a
 * {@code javafx.animation.Timeline} used as a node's timer.
 *
 * <h2>Why not a Timeline</h2>
 * A {@code Timeline} only ticks while the JavaFX toolkit is running, and a node that keeps its
 * timer <em>as</em> its running flag ("running means {@code timeline != null}") cannot answer
 * whether it is live without one. Both make the node's lifecycle depend on being drawn. This
 * class ticks wherever the node is, so a node owns its own running state and its own clock,
 * and its controls — if it has any — are updated separately through
 * {@link BaseNode#present(Runnable)}.
 *
 * <h2>Threading</h2>
 * Ticks do <b>not</b> arrive on the JavaFX Application Thread. A single shared daemon thread
 * keeps the schedule and does nothing else; each tick runs on its own virtual thread, so one
 * node's slow tick cannot delay another node's clock. A tick that is still running when the
 * next is due is <em>skipped</em> rather than overlapped, so a tick body needs no locking of
 * its own against itself. Anything a tick wants to show goes through
 * {@link BaseNode#present(Runnable)}, which is what puts it back on the right thread.
 *
 * <h2>Lifecycle</h2>
 * A timer is a resource: {@link #stop()} it from the node's {@code onRemoved()} so it cannot
 * keep firing as a zombie. {@code stop()} is idempotent and cheap, and never waits for a tick
 * already in flight — it belongs in the fast half of teardown, not in
 * {@code releaseResources()} (see {@code docs/engine/node-lifecycle.md}).
 *
 * <pre>{@code
 * private final NodeTimer clock = new NodeTimer("MyResource");
 *
 * private void start() {
 *     running = true;
 *     clock.start(1000, this::tick);
 *     present(() -> statusLabel.setText("Running"));
 * }
 *
 * // and, overriding BaseNode:
 * protected void onRemoved() {
 *     running = false;
 *     clock.stop();
 *     present(() -> statusLabel.setText("Stopped"));
 * }
 * }</pre>
 */
public final class NodeTimer {

    private static final Logger log = Log.get(NodeTimer.class);

    /**
     * One daemon thread for every timer in the process. It only hands each due tick to a virtual
     * thread, so it is never the thing a tick body waits on, and daemon so a timer nobody stopped
     * cannot hold the JVM open.
     */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(schedulerThreadFactory());

    private final String owner;
    private final AtomicBoolean tickInFlight = new AtomicBoolean();

    private ScheduledFuture<?> schedule;

    /**
     * @param owner a label for log messages — conventionally the node's name or simple class name
     */
    public NodeTimer(String owner) {
        this.owner = owner;
    }

    /**
     * Starts ticking every {@code periodMillis}, the first tick one period from now. Any schedule
     * already running is cancelled first, so this always leaves exactly one clock behind.
     *
     * @param periodMillis milliseconds between ticks; must be positive
     * @param tick         the work to run each period, off the FX thread
     * @throws IllegalArgumentException if {@code periodMillis} is not positive
     */
    public synchronized void start(long periodMillis, Runnable tick) {
        if (periodMillis <= 0) {
            throw new IllegalArgumentException("timer period must be positive, was " + periodMillis);
        }
        stop();
        schedule = SCHEDULER.scheduleAtFixedRate(
                () -> dispatch(tick), periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Cancels the schedule. Idempotent, and safe from any thread — including from inside a tick,
     * which is how a self-limiting timer stops itself. A tick already running is left to finish;
     * no further tick is dispatched.
     */
    public synchronized void stop() {
        if (schedule != null) {
            schedule.cancel(false);
            schedule = null;
        }
    }

    /**
     * @return true while a schedule is in place, i.e. between {@link #start} and {@link #stop}
     */
    public synchronized boolean isRunning() {
        return schedule != null;
    }

    /**
     * Hands one due tick to a virtual thread, unless the previous one has not finished. Skipping
     * rather than queueing keeps a tick body single-threaded against itself and stops a node whose
     * tick outlasts its period building an unbounded backlog.
     */
    private void dispatch(Runnable tick) {
        if (!tickInFlight.compareAndSet(false, true)) {
            log.debug("{} timer skipped a tick: the previous one is still running", owner);
            return;
        }
        Thread.ofVirtual().name("housegraph-tick-" + owner).start(() -> {
            try {
                tick.run();
            } catch (RuntimeException | Error e) {
                log.warn("{} timer tick failed: {}", owner, e.toString());
            } finally {
                tickInFlight.set(false);
            }
        });
    }

    private static ThreadFactory schedulerThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "housegraph-node-timers");
            thread.setDaemon(true);
            return thread;
        };
    }
}
