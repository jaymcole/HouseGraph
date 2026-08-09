package io.github.jaymcole.housegraph.cli.commands;

import io.github.jaymcole.housegraph.cli.Args;
import io.github.jaymcole.housegraph.cli.Command;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.logging.Logging;
import io.github.jaymcole.housegraph.remote.GraphProcess;
import io.github.jaymcole.housegraph.remote.GraphRepository;
import io.github.jaymcole.housegraph.remote.RemoteConfig;
import io.github.jaymcole.housegraph.remote.RemoteDeployment;
import io.github.jaymcole.housegraph.remote.RemoteState;
import io.github.jaymcole.housegraph.remote.Supervisor;
import io.github.jaymcole.housegraph.storage.AppDirectories;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The long-running command: poll the configured repositories, and keep their graphs running.
 *
 * <h2>The loop</h2>
 * Sync on start, then once every {@code pollSeconds}: ask each remote whether it has moved, and only
 * touch the disk when it has. In between, {@link Supervisor#tick()} restarts anything that died. The
 * supervisor's tick is far cheaper than a git call, so it runs on a short fixed beat while the git
 * poll keeps to the configured interval — a crashed graph comes back in seconds rather than waiting
 * out a minute-long sleep.
 *
 * <h2>Stopping</h2>
 * A shutdown hook stops every child before the daemon exits, so a {@code launchctl unload} or a
 * reboot tears graphs down the same way closing the window does. Without it the children would be
 * orphaned and keep running with nothing supervising them.
 */
public final class DaemonCommand implements Command {

    private static final Logger log = Log.get(DaemonCommand.class);

    /** How often to reap and restart children, independent of the git poll interval. */
    private static final long TICK_SECONDS = 2;

    private final PrintStream out;

    public DaemonCommand(PrintStream out) {
        this.out = out;
    }

    @Override
    public String name() {
        return "daemon";
    }

    @Override
    public String summary() {
        return "Keep the configured repositories' graphs running, restarting them when they change";
    }

    @Override
    public String usage() {
        return "  daemon [--once]\n\n"
                + "--once syncs, starts everything, and returns — for checking the setup works.\n"
                + "Reads config/remote.json. Runs until stopped; install it as a LaunchAgent to\n"
                + "start at login. See docs/architecture/deployment.md.";
    }

    @Override
    public int run(Args args) {
        Logging.bootstrap(AppDirectories.get().logs());

        RemoteConfig config = RemoteConfig.load();
        if (config.repositories().isEmpty()) {
            out.println("No repositories configured in config/remote.json.");
            out.println("Run `housegraph doctor` for what that file needs.");
            return 2;
        }

        GraphProcess.Launcher launcher = GraphProcess.defaultLauncher();
        if (launcher == null) {
            // Without a jar there is nothing to hand a child JVM. Better to say so now than to fail
            // once per graph, forever, in a log nobody is reading yet.
            out.println("The daemon has to run from a jar so it can start graph processes.");
            out.println("Build one with ./gradlew :app:shadowJar and run that.");
            return 2;
        }

        RemoteDeployment deployment = new RemoteDeployment(config, RemoteState.load());
        List<GraphRepository> repositories = deployment.repositories();
        Supervisor supervisor = new Supervisor(launcher);
        CountDownLatch stop = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Stopping supervised graphs");
            stop.countDown();
            supervisor.stopAll();
            Logging.shutdown();
        }, "housegraph-daemon-shutdown"));

        log.info("Watching {} repository/ies every {}s", repositories.size(), config.pollSeconds());
        // Forced on the first pass: the state file may remember a commit whose mirror has since been
        // deleted, and "unchanged" would then start nothing at all.
        boolean force = true;
        long nextPollAt = 0;

        while (stop.getCount() > 0) {
            if (System.currentTimeMillis() >= nextPollAt) {
                pollOnce(deployment, repositories, supervisor, force);
                force = false;
                nextPollAt = System.currentTimeMillis() + config.pollSeconds() * 1000L;
                if (args.isEnabled("once")) {
                    supervisor.tick();
                    out.println("Started " + supervisor.graphs().size() + " graph(s).");
                    return 0;
                }
            }
            supervisor.tick();
            try {
                if (stop.await(TICK_SECONDS, TimeUnit.SECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return 0;
    }

    /**
     * One sync pass across every repository.
     *
     * <p>The supervised set is rebuilt from all repositories together, so a graph removed from one
     * of them stops even though the others are unchanged. A repository that couldn't be reached
     * contributes the graphs it is already running rather than none — a network blip must not take
     * down working graphs.
     */
    private void pollOnce(RemoteDeployment deployment,
                          List<GraphRepository> repositories,
                          Supervisor supervisor,
                          boolean force) {
        List<Path> wanted = new ArrayList<>();
        boolean anyChanged = false;

        for (GraphRepository repository : repositories) {
            Optional<RemoteDeployment.Deployment> result = deployment.refresh(repository, force);
            if (result.isEmpty()) {
                supervisor.graphs().stream()
                        .filter(graph -> graph.startsWith(repository.cloneDirectory()))
                        .forEach(wanted::add);
                continue;
            }
            wanted.addAll(result.get().graphs());
            anyChanged |= result.get().changed();
        }

        supervisor.setGraphs(wanted);
        if (anyChanged) {
            // A new commit can have changed any graph in the repository, and can have brought a new
            // node library with it — which only a fresh JVM picks up. Restarting all of them is the
            // one behaviour that is correct in every case.
            log.info("Repository contents changed; restarting {} graph(s)", wanted.size());
            supervisor.restartAll();
        }
    }
}
