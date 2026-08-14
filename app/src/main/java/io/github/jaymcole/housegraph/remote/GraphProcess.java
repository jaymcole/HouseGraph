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
import java.util.concurrent.TimeUnit;

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
     * and force only if it won't go.
     *
     * <p>The wait is what makes the hook worth having. Killing immediately would skip every node's
     * {@code onRemoved()} — connections, child processes and timers all left to the OS — which is
     * exactly the leak the hook exists to prevent.
     *
     * @param process        the child to stop
     * @param timeoutSeconds how long to let it shut down cleanly
     * @return true if it exited on its own, false if it had to be forced
     */
    public static boolean stop(Process process, long timeoutSeconds) {
        if (!process.isAlive()) {
            return true;
        }
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
        return false;
    }
}
