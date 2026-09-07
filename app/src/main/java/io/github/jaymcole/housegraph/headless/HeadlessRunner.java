package io.github.jaymcole.housegraph.headless;

import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.logging.Logging;
import io.github.jaymcole.housegraph.modules.ModuleLibrary;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.plugin.PluginInstaller;
import io.github.jaymcole.housegraph.plugin.PluginLoader;
import io.github.jaymcole.housegraph.remote.ExitCodes;
import io.github.jaymcole.housegraph.storage.AppDirectories;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one graph with no window: the whole program behind {@code housegraph run --headless <graph>}.
 *
 * <h2>The run</h2>
 * Stand up logging and the node-library class loader, open the graph (see {@link HeadlessGraph}),
 * then stay alive until the process is signalled, tear down, and return an {@link ExitCodes} value.
 * The same shape as {@code App} around its canvas, minus the canvas — a graph that has been loaded
 * and resumed needs no display to keep working, only something to keep the JVM from exiting.
 *
 * <h2>Exit codes</h2>
 * <ul>
 *   <li>{@link ExitCodes#OK} — shut down cleanly. The supervisor restarts on this, because a graph
 *       is meant to stay up.</li>
 *   <li>{@link ExitCodes#CONFIGURATION_ERROR} — no graph was named, or the file is missing,
 *       unreadable or unparseable. All of those will still be true next time, and the code is what
 *       stops a permanent fault becoming a restart loop.</li>
 * </ul>
 * A node library that could not be installed is <b>not</b> one of them: its nodes load as
 * placeholders, the log says which library and why, and the rest of the graph runs. Nor is a node
 * that throws while resuming — the graph is partly live, which is better than dead.
 *
 * <h2>Shutdown</h2>
 * {@code kill} is how the supervisor restarts a graph, so a shutdown hook is not optional: without
 * one, no node's {@code onRemoved()} would run and the tail of the log would never reach disk.
 * Unlike the windowed app there is no FX thread to hand teardown to, so the hook simply performs it,
 * and {@link #shutdown()} is written so that whichever of the two threads arrives first does the
 * work while the other waits — bounded, so one wedged node delays the restart rather than blocking
 * it.
 */
public final class HeadlessRunner {

    private static final Logger log = Log.get(HeadlessRunner.class);

    /**
     * How long the thread that did not perform teardown waits for the one that did.
     *
     * <p>Derived from {@code NodeGraph.DEFAULT_RELEASE_TIMEOUT} the same way
     * {@code App.SHUTDOWN_TIMEOUT_SECONDS} is: the engine already bounds each node's
     * {@code releaseResources()} and runs them concurrently, so a whole graph's slow half costs one
     * release timeout however many nodes there are. This only has to be that plus room for the fast
     * half, closing the plugin loader and closing the log file.
     */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = NodeGraph.DEFAULT_RELEASE_TIMEOUT.toSeconds() + 10;

    private final File graphFile;
    private final PluginCatalog pluginCatalog;
    private final PluginLoader pluginLoader;
    private final ShutdownSignal signal;

    /** Whichever thread wins this performs teardown; the other waits on {@link #stopped}. */
    private final AtomicBoolean tearingDown = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);

    /** Volatile because the shutdown hook reads it on a thread that never assigned it. */
    private volatile NodeGraph graph;

    HeadlessRunner(File graphFile, PluginCatalog pluginCatalog, PluginLoader pluginLoader, ShutdownSignal signal) {
        this.graphFile = graphFile;
        this.pluginCatalog = pluginCatalog;
        this.pluginLoader = pluginLoader;
        this.signal = signal;
    }

    /**
     * Runs {@code graphFile} to completion.
     *
     * <p>Returns only once the process has been signalled and the graph has been torn down, so a
     * caller does nothing after this but exit with what it returned. When the stop came from a
     * signal the JVM is already on its way out and its exit status is the signal's; the returned
     * code is what matters for every other way this ends.
     *
     * @param graphFile the graph to run, or null when the command line named none
     * @return the exit code
     */
    public static int run(File graphFile) {
        if (graphFile == null) {
            // Answered before anything is stood up, which costs nothing: LogManager always has a
            // console sink, and nobody who mistyped a command line is reading a log file for it.
            // The flag is spelled out rather than read from cli/CommandLine, which owns it: this
            // package is a sibling of that one, and a command that wanted to start a headless run
            // would close the loop into a cycle.
            log.error("No graph file given. Usage: housegraph run --headless <graph.json>");
            return ExitCodes.CONFIGURATION_ERROR;
        }
        // Same file the windowed app writes, deliberately: a supervised child's output is merged
        // into the daemon's log by GraphProcess either way, and writing the same file as well keeps
        // what an operator finds on disk identical to what a windowed child left there. Idempotent,
        // which is what lets a second entry point call it at all.
        Logging.bootstrap(AppDirectories.get().logs());

        // Read purely from local state, like every other startup path: no network call, and pruning
        // before any loader exists because a loader holds open handles on the jars it would delete.
        PluginCatalog catalog = PluginCatalog.load();
        PluginInstaller.pruneSupersededVersions(catalog);
        PluginLoader loader = PluginLoader.from(catalog, HeadlessRunner.class.getClassLoader());

        return new HeadlessRunner(graphFile, catalog, loader, new ShutdownSignal()).call();
    }

    /**
     * The run itself, given collaborators that are already built.
     *
     * @return the exit code
     */
    int call() {
        // On this thread, because this is the one that creates the engine's threads: NodeGraph's
        // executors are built two lines below, and a virtual thread inherits the context loader of
        // whatever created it. That is what a library's own ServiceLoader or Class.forName lookups
        // need when they run inside a node's process(). App sets it inside start() for the same
        // reason and a different answer - there the creating thread is the FX thread, not the one
        // main() ran on. Here it is whichever thread called this, which on the real path is main().
        Thread.currentThread().setContextClassLoader(pluginLoader.classLoader());

        graph = new NodeGraph();
        NodeRegistry registry = new NodeRegistry(pluginLoader.scanRoots());

        // Installed before the graph is opened: a node resumed during the open may already be
        // holding a socket by the time the first signal arrives.
        installShutdownHook();

        try {
            // The modules the graph references are looked for in the machine's modules directory and
            // beside the graph itself, the same two places `housegraph validate` looks — a deployed
            // repository commonly carries a module and its consumer in one folder.
            HeadlessGraph.open(graphFile, graph, registry, pluginCatalog,
                    ModuleLibrary.forGraph(graphFile, registry));
        } catch (IOException | RuntimeException failure) {
            log.error("Cannot run " + graphFile + ": the graph could not be loaded", failure);
            shutdown();
            return ExitCodes.CONFIGURATION_ERROR;
        }

        log.info("Running {} headless, with no window. Signal the process to stop it.", graphFile.getName());
        signal.await();
        shutdown();
        return ExitCodes.OK;
    }

    /**
     * Makes a signalled JVM shut down the way a clean stop does.
     *
     * <p>{@link ShutdownSignal#release()} first, so the thread parked in {@link #call()} is not left
     * waiting on a latch nothing will ever count down; the teardown that follows is the same call,
     * and the two threads sort out between them which one performs it.
     */
    private void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            signal.release();
            shutdown();
        }, "housegraph-headless-shutdown"));
    }

    /**
     * Disposes the graph, releases the node-library jars and closes the log file — the same three
     * steps, in the same order, as {@code App.stop()}.
     *
     * <p>Runs once. The second caller waits for the first rather than returning into an exit that
     * would kill teardown halfway through, and gives up after {@link #SHUTDOWN_TIMEOUT_SECONDS}
     * because a node that refuses to stop must delay the restart, not prevent it.
     */
    private void shutdown() {
        if (!tearingDown.compareAndSet(false, true)) {
            awaitTeardown();
            return;
        }
        try {
            if (graph != null) {
                graph.dispose();
            }
            // On Windows an open jar can be neither deleted nor overwritten, so the next run can
            // only prune or replace a library if this one let go of it.
            pluginLoader.close();
            Logging.shutdown();
        } finally {
            stopped.countDown();
        }
    }

    private void awaitTeardown() {
        try {
            if (!stopped.await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // Not through the log: its file sink may already be closing.
                System.err.println("HouseGraph: shutdown timed out after "
                        + SHUTDOWN_TIMEOUT_SECONDS + "s; exiting anyway");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
