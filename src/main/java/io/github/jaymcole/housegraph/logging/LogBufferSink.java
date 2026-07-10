package io.github.jaymcole.housegraph.logging;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A {@link LogSink} that retains the most recent records in memory and notifies live
 * listeners as new ones arrive. This is what lets the log window be <em>opened and closed
 * without losing logs</em>: the buffer keeps capturing regardless of whether any window is
 * watching, so a freshly-opened window can replay the whole {@link #snapshot()} and then
 * follow along via a {@linkplain #addListener listener}.
 *
 * <p>The buffer is bounded to a fixed capacity (a ring): once full, the oldest record is
 * evicted as each new one arrives, so memory stays flat during a long-running session.
 *
 * <p><b>Threading.</b> Records are published from arbitrary execution threads; listeners
 * are typically added/removed and invoked toward a UI thread. The record deque is guarded
 * by an intrinsic lock, and listeners live in a {@link CopyOnWriteArrayList}. Listeners are
 * invoked <em>outside</em> the lock (on the publishing thread), so a listener must marshal
 * to its own toolkit thread and must not call back into the buffer in a way that would
 * deadlock.
 */
public final class LogBufferSink extends AbstractLogSink {

    private final int capacity;
    private final Deque<LogRecord> records;
    private final List<Consumer<LogRecord>> listeners = new CopyOnWriteArrayList<>();

    /**
     * @param capacity the maximum number of records retained (oldest evicted past this)
     * @param level    the minimum level captured into the buffer
     */
    public LogBufferSink(int capacity, LogLevel level) {
        super("Window", level);
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.records = new ArrayDeque<>(capacity);
    }

    /**
     * The retained capacity (ring size).
     *
     * @return the maximum number of buffered records
     */
    public int capacity() {
        return capacity;
    }

    @Override
    public void publish(LogRecord record) {
        synchronized (records) {
            if (records.size() == capacity) {
                records.removeFirst();
            }
            records.addLast(record);
        }
        for (Consumer<LogRecord> listener : listeners) {
            listener.accept(record);
        }
    }

    /**
     * A point-in-time copy of the buffered records, oldest first. A window uses this to
     * populate itself on open — including everything captured while it was closed.
     *
     * @return the current records, oldest first
     */
    public List<LogRecord> snapshot() {
        synchronized (records) {
            return new ArrayList<>(records);
        }
    }

    /** Discards all buffered records (e.g. the window's "Clear" button). Listeners are not notified. */
    public void clear() {
        synchronized (records) {
            records.clear();
        }
    }

    /**
     * Registers a listener invoked for every record published <em>after</em> this call.
     * Combine with {@link #snapshot()} to get the full history plus live updates with no
     * gap or duplication is the caller's responsibility (snapshot first, then add).
     *
     * @param listener the callback to invoke per new record
     */
    public void addListener(Consumer<LogRecord> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Unregisters a previously-added listener (e.g. when a window closes).
     *
     * @param listener the callback to remove
     */
    public void removeListener(Consumer<LogRecord> listener) {
        listeners.remove(listener);
    }
}
