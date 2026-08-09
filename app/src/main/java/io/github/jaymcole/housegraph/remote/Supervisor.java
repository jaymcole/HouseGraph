package io.github.jaymcole.housegraph.remote;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps a set of graphs running, and swaps them out when the set changes.
 *
 * <h2>Restart, don't reload</h2>
 * When a repository moves, every graph from it is stopped and started again in a fresh JVM. That is
 * blunter than reloading a graph in place, and deliberately so: {@code NodeGraph.dispose()} shuts
 * down its executors permanently, so an in-place reload would need a new engine anyway, and it still
 * could not pick up a node-library update — {@code App.tryReloadNodeLibraries} refuses to rebuild the
 * class loader while library nodes are live. A new process picks up new graphs and new libraries by
 * the same mechanism, with no second code path that only works sometimes.
 *
 * <h2>Backoff is not optional</h2>
 * A graph that fails immediately — a missing secret, a port already bound — would otherwise be
 * restarted as fast as the JVM can start, pinning a core and burying the real error under thousands
 * of identical log lines. The delay grows to a cap and resets once a run has lasted long enough to
 * count as healthy, so an occasional crash still recovers promptly while a permanent fault settles
 * into a slow, readable retry.
 *
 * <p>Not thread-safe: the daemon drives it from one loop.
 */
public final class Supervisor {

    private static final Logger log = Log.get(Supervisor.class);

    static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAXIMUM_BACKOFF = Duration.ofSeconds(60);

    /** How long a child must stay up before its next failure is treated as a fresh problem. */
    static final Duration HEALTHY_AFTER = Duration.ofSeconds(60);

    /** How long a child gets to shut down cleanly before it is killed. */
    static final long STOP_TIMEOUT_SECONDS = 20;

    /** One graph being kept alive. */
    private static final class Supervised {
        private final Path graph;
        private Process process;
        private long startedAtMillis;
        private Duration backoff = INITIAL_BACKOFF;
        private long retryAtMillis;
        private boolean abandoned;

        private Supervised(Path graph) {
            this.graph = graph;
        }
    }

    private final GraphProcess.Launcher launcher;
    private final java.util.function.LongSupplier clock;
    private final Map<Path, Supervised> supervised = new LinkedHashMap<>();

    public Supervisor(GraphProcess.Launcher launcher) {
        this(launcher, System::currentTimeMillis);
    }

    /** With an injectable clock, so backoff can be tested without waiting out real delays. */
    Supervisor(GraphProcess.Launcher launcher, java.util.function.LongSupplier clock) {
        this.launcher = launcher;
        this.clock = clock;
    }

    /** The graphs currently under supervision, in the order they were set. */
    public List<Path> graphs() {
        return List.copyOf(supervised.keySet());
    }

    /** Whether {@code graph} has a live process right now. */
    public boolean isRunning(Path graph) {
        Supervised entry = supervised.get(graph);
        return entry != null && entry.process != null && entry.process.isAlive();
    }

    /**
     * Makes the supervised set exactly {@code graphs}, stopping anything no longer wanted and
     * starting anything new. Graphs already running are left alone.
     *
     * @param graphs the graphs that should be running
     */
    public void setGraphs(List<Path> graphs) {
        for (Path existing : List.copyOf(supervised.keySet())) {
            if (!graphs.contains(existing)) {
                log.info("No longer running {}", existing.getFileName());
                stop(supervised.remove(existing));
            }
        }
        for (Path graph : graphs) {
            supervised.computeIfAbsent(graph, Supervised::new);
        }
    }

    /**
     * Stops and immediately restarts every supervised graph — what a repository change calls for,
     * since a new commit can have changed any graph in it as well as which libraries they need.
     *
     * <p>Restarting resets each graph's backoff: the code has changed, so previous failures say
     * nothing about this attempt, and a graph that had settled into a slow retry deserves to be
     * tried again at once.
     */
    public void restartAll() {
        for (Supervised entry : supervised.values()) {
            stop(entry);
            entry.backoff = INITIAL_BACKOFF;
            entry.retryAtMillis = 0;
            entry.abandoned = false;
        }
    }

    /**
     * One pass of the keep-alive loop: reap anything that exited, and start anything that should be
     * running and isn't. Cheap and idempotent — call it as often as you like.
     */
    public void tick() {
        for (Supervised entry : supervised.values()) {
            if (entry.abandoned) {
                continue;
            }
            if (entry.process != null && entry.process.isAlive()) {
                continue;
            }
            if (entry.process != null) {
                reap(entry);
                if (entry.abandoned) {
                    continue;
                }
            }
            if (clock.getAsLong() >= entry.retryAtMillis) {
                start(entry);
            }
        }
    }

    /** Stops every supervised graph, for daemon shutdown. */
    public void stopAll() {
        supervised.values().forEach(this::stop);
    }

    private void reap(Supervised entry) {
        int code = entry.process.exitValue();
        boolean healthy = clock.getAsLong() - entry.startedAtMillis >= HEALTHY_AFTER.toMillis();
        entry.process = null;

        if (!ExitCodes.shouldRestart(code)) {
            log.error("{} exited with a configuration error ({}); not restarting it until the "
                    + "repository changes", entry.graph.getFileName(), code);
            entry.abandoned = true;
            return;
        }

        if (code == ExitCodes.RESTART_REQUESTED) {
            log.info("{} asked to be restarted", entry.graph.getFileName());
            entry.backoff = INITIAL_BACKOFF;
            entry.retryAtMillis = 0;
            return;
        }

        if (healthy) {
            // It ran for a good while before dying, so this is a new fault rather than a failure to
            // start at all. Retry immediately and forget the old backoff.
            log.warn("{} exited with {} after running normally; restarting",
                    entry.graph.getFileName(), code);
            entry.backoff = INITIAL_BACKOFF;
            entry.retryAtMillis = 0;
            return;
        }

        log.warn("{} exited with {} after {}s; retrying in {}s", entry.graph.getFileName(), code,
                (clock.getAsLong() - entry.startedAtMillis) / 1000, entry.backoff.toSeconds());
        entry.retryAtMillis = clock.getAsLong() + entry.backoff.toMillis();
        entry.backoff = Duration.ofMillis(Math.min(MAXIMUM_BACKOFF.toMillis(), entry.backoff.toMillis() * 2));
    }

    private void start(Supervised entry) {
        try {
            entry.process = launcher.launch(entry.graph);
            entry.startedAtMillis = clock.getAsLong();
            GraphProcess.pumpOutput(entry.process, entry.graph.getFileName().toString());
        } catch (IOException e) {
            log.error("Could not start {}", entry.graph, e);
            entry.retryAtMillis = clock.getAsLong() + entry.backoff.toMillis();
            entry.backoff = Duration.ofMillis(
                    Math.min(MAXIMUM_BACKOFF.toMillis(), entry.backoff.toMillis() * 2));
        }
    }

    private void stop(Supervised entry) {
        if (entry == null || entry.process == null) {
            return;
        }
        GraphProcess.stop(entry.process, STOP_TIMEOUT_SECONDS);
        entry.process = null;
    }

    /** The graphs currently abandoned after a configuration error, for {@code doctor}-style output. */
    public List<Path> abandoned() {
        List<Path> result = new ArrayList<>();
        supervised.forEach((graph, entry) -> {
            if (entry.abandoned) {
                result.add(graph);
            }
        });
        return result;
    }
}
