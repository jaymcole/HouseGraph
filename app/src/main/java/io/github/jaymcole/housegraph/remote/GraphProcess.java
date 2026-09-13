package io.github.jaymcole.housegraph.remote;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One supervised HouseGraph instance: a child JVM running exactly one graph.
 *
 * <h2>Why a process per graph rather than one process with many</h2>
 * The app opens one graph at a time and always has; a second graph in the same JVM would need a
 * multi-document canvas that does not exist. More importantly, isolation is the point — one graph
 * whose node wedges or leaks takes only itself down, and restarting it does not interrupt the
 * others. It also makes a node-library update work: {@code App.tryReloadNodeLibraries} refuses to
 * hot-reload while library nodes are live, so a fresh JVM is the only thing that reliably picks one
 * up, and here that costs one graph's downtime instead of the whole machine's.
 */
public final class GraphProcess {

    private static final Logger log = Log.get(GraphProcess.class);

    /**
     * How long a force-killed child gets to actually disappear. A {@code SIGKILL} lands in
     * milliseconds unless the process is stuck in the kernel, so this is a bound on a pathology
     * rather than a wait anything normally spends.
     */
    static final long FORCE_KILL_TIMEOUT_SECONDS = 10;

    /** How long a graph's surviving subprocess gets to exit on a signal before it is killed. */
    static final long DESCENDANT_TIMEOUT_SECONDS = 5;

    /**
     * Launches a child. Injected so {@link Supervisor} can be tested without spawning JVMs — the
     * restart, backoff and shutdown logic is the part worth testing and none of it is about
     * {@code ProcessBuilder}.
     */
    @FunctionalInterface
    public interface Launcher {
        /**
         * Starts a HouseGraph process for one graph.
         *
         * @param graph the save file to open
         * @return the running process
         * @throws IOException if it can't be started
         */
        Process launch(Path graph) throws IOException;
    }

    private GraphProcess() {
    }

    /**
     * The default launcher: this same jar, this same JVM, run on one graph.
     *
     * <p>The java binary comes from {@link ProcessHandle#info()} and the jar from where this class
     * was loaded, so a child always matches the parent — no {@code JAVA_HOME} to get wrong, and no
     * path in a config file to go stale after an upgrade.
     *
     * @return a launcher, or null when this build isn't running from a jar (an IDE run)
     */
    public static Launcher defaultLauncher() {
        Path jar = runningJar();
        if (jar == null) {
            return null;
        }
        String java = ProcessHandle.current().info().command().orElse("java");
        return graph -> {
            List<String> command = new ArrayList<>(List.of(java));
            // A child must read the same catalog, secrets and logs as the daemon that started it.
            // The environment variable carries across on its own; a -Dhousegraph.home set by
            // --home does not, so it is passed on explicitly or the child would silently use a
            // different data directory.
            String home = System.getProperty("housegraph.home");
            if (home != null && !home.isBlank()) {
                command.add("-Dhousegraph.home=" + home);
            }
            // Lets a node distinguish "the supervisor opened me" from "a person opened me" via
            // sdk.RuntimeMode - see DaemonStartTriggerNode. Unconditional, unlike housegraph.home
            // above: every graph this launcher starts is supervised, so there is nothing to read
            // from the parent - the daemon process itself is not "a daemon graph", only the
            // children it spawns are.
            command.add("-Dhousegraph.daemon=true");
            command.addAll(List.of("-jar", jar.toString(),
                    "run", graph.toString()));
            log.info("Starting {}", graph.getFileName());
            return new ProcessBuilder(command)
                    // The child logs through the same LogManager into the same file; merging its
                    // streams here keeps anything it writes before logging is up (a JVM error, a
                    // JavaFX toolkit failure) from being thrown away.
                    .redirectErrorStream(true)
                    .start();
        };
    }

    /**
     * The jar this class was loaded from, or null when running from exploded classes.
     *
     * @return the jar path, or null
     */
    public static Path runningJar() {
        try {
            var source = GraphProcess.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path path = Paths.get(source.getLocation().toURI());
            return Files.isRegularFile(path) && path.toString().endsWith(".jar") ? path : null;
        } catch (Exception e) {
            log.debug("Could not determine the running jar", e);
            return null;
        }
    }

    /**
     * Copies a child's merged output into this process's log, so a supervised graph's failures are
     * visible where the operator is already looking.
     *
     * @param process the child
     * @param name    what to label its lines with
     * @return the started daemon thread, so a caller can join it if it wants to
     */
    public static Thread pumpOutput(Process process, String name) {
        Thread pump = new Thread(() -> {
            try (InputStream stream = process.getInputStream();
                 var reader = new java.io.BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[{}] {}", name, line);
                }
            } catch (IOException e) {
                // Expected when the child is killed mid-line; not worth an error.
                log.debug("Output pump for {} ended", name, e);
            } catch (UncheckedIOException e) {
                log.debug("Output pump for {} ended", name, e);
            }
        }, "graph-output-" + name);
        pump.setDaemon(true);
        pump.start();
        return pump;
    }

    /**
     * Stops a child the way {@code App}'s shutdown hook expects: a signal first, so teardown runs,
     * and force only if it won't go — then does not return until the process is actually gone.
     *
     * <p>The wait is what makes the hook worth having. Killing immediately would skip every node's
     * {@code onRemoved()} — connections, child processes and timers all left to the OS — which is
     * exactly the leak the hook exists to prevent.
     *
     * <h4>Gone means gone, not asked to go</h4>
     * {@code destroyForcibly()} only <em>requests</em> the kill; it returns before the process has
     * died. Reporting success there would let {@link Supervisor} start a replacement graph while the
     * old one still held its ports, and a node that binds one would fail on the new copy for reasons
     * nothing in its own log explains. So a forced kill is waited on too, and a process that survives
     * even that is reported as still running rather than quietly assumed dead.
     *
     * <h4>And its subprocesses with it</h4>
     * A graph's own subprocess — a web server, a language runtime a node shells out to — is
     * <b>not</b> cleaned up when the graph's JVM dies. It is reparented and keeps running, holding
     * whatever port it bound. So the child's descendants are snapshotted before it is signalled
     * (afterwards they can no longer be found from its handle) and stopped alongside it.
     *
     * @param process        the child to stop
     * @param timeoutSeconds how long to let it shut down cleanly before it is killed
     * @return true when the process is confirmed gone, false when it could not be
     */
    public static boolean stop(Process process, long timeoutSeconds) {
        if (!process.isAlive()) {
            return true;
        }
        List<ProcessHandle> descendants = descendantsOf(process);
        boolean stopped = terminate(process, timeoutSeconds);
        reap(descendants);
        return stopped;
    }

    /** Signals, waits, kills, waits again. See {@link #stop}. */
    private static boolean terminate(Process process, long timeoutSeconds) {
        process.destroy();
        try {
            if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.warn("A graph process did not stop within {}s; killing it", timeoutSeconds);
        process.destroyForcibly();
        try {
            if (process.waitFor(FORCE_KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.error("A graph process survived being killed; whatever it holds — ports, sockets, files "
                + "— is still held, so its replacement is not started yet");
        return false;
    }

    /**
     * The child's descendants, or none when this {@link Process} cannot produce a handle.
     *
     * <p>{@code Process.descendants()} is a default method over {@code toHandle()}, which an
     * implementation is free not to support — the JDK's own default throws. Treating that as "no
     * descendants" keeps a caller holding such a process working exactly as it did before.
     */
    private static List<ProcessHandle> descendantsOf(Process process) {
        try {
            return process.descendants().toList();
        } catch (UnsupportedOperationException e) {
            log.debug("This process cannot enumerate descendants; none will be cleaned up", e);
            return List.of();
        }
    }

    /**
     * Stops anything the graph spawned that outlived it, signal first and kill after one shared
     * grace period — shared rather than per-process so a graph with several subprocesses does not
     * multiply the wait.
     */
    private static void reap(List<ProcessHandle> descendants) {
        List<ProcessHandle> surviving = descendants.stream().filter(ProcessHandle::isAlive).toList();
        if (surviving.isEmpty()) {
            return;
        }
        for (ProcessHandle handle : surviving) {
            log.warn("A graph's subprocess (pid {}) outlived it; stopping it", handle.pid());
            handle.destroy();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DESCENDANT_TIMEOUT_SECONDS);
        for (ProcessHandle handle : surviving) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException e) {
                log.debug("Subprocess {} did not exit within the grace period", handle.pid(), e);
            }
        }
        surviving.stream().filter(ProcessHandle::isAlive).forEach(handle -> {
            log.warn("Subprocess {} ignored the signal; killing it", handle.pid());
            handle.destroyForcibly();
        });
    }
}
