package io.github.jaymcole.housegraph.cli.commands;

import io.github.jaymcole.housegraph.cli.Args;
import io.github.jaymcole.housegraph.cli.Command;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.logging.Logging;
import io.github.jaymcole.housegraph.remote.ExitCodes;
import io.github.jaymcole.housegraph.remote.GraphProcess;
import io.github.jaymcole.housegraph.remote.GraphRepository;
import io.github.jaymcole.housegraph.remote.RemoteConfig;
import io.github.jaymcole.housegraph.remote.RemoteDeployment;
import io.github.jaymcole.housegraph.remote.RemoteState;
import io.github.jaymcole.housegraph.remote.SelfUpdater;
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
 * <h2>Updating itself</h2>
 * When {@code selfUpdate.enabled} is set, the same loop also asks GitHub for HouseGraph's latest
 * release on its own, much longer, interval. Applying one replaces the jar and returns
 * {@link ExitCodes#RESTART_REQUESTED}, because a JVM cannot become a different build of itself — the
 * supervisor that keeps the daemon alive is what starts it again, onto the new jar. See
 * {@link SelfUpdater}.
 *
 * <h2>Stopping</h2>
 * A shutdown hook stops every child before the daemon exits, so a {@code launchctl unload} or a
 * reboot tears graphs down the same way closing the window does. Without it the children would be
 * orphaned and keep running with nothing supervising them. It is also what makes the update restart
 * clean: the graphs come down through their normal teardown, then come back up under the new jar.
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
                + "--once syncs, starts everything, and returns — for checking the setup works. It\n"
                + "never applies a HouseGraph update, since that would mean exiting to restart.\n"
                + "Reads config/remote.json. Runs until stopped; install it as a LaunchAgent to\n"
                + "start at login. See docs/guides/server-setup.md.";
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

        RemoteState state = RemoteState.load();
        RemoteDeployment deployment = new RemoteDeployment(config, state);
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
        SelfUpdater updater = startUpdater(config, state, args.isEnabled("once"));
        // Forced on the first pass: the state file may remember a commit whose mirror has since been
        // deleted, and "unchanged" would then start nothing at all.
        boolean force = true;
        long nextPollAt = 0;
        // Checked as soon as the loop starts: a machine that has been off for a month should not
        // wait out a whole interval before catching up, and a stored ETag makes the check free when
        // there is nothing new.
        long nextUpdateCheckAt = 0;

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
            if (updater != null && System.currentTimeMillis() >= nextUpdateCheckAt) {
                nextUpdateCheckAt =
                        System.currentTimeMillis() + config.selfUpdate().checkSeconds() * 1000L;
                if (checkForUpdate(updater)) {
                    // The shutdown hook stops the graphs on the way out, and the supervisor that
                    // keeps this process alive starts it again on the jar just installed.
                    log.info("Exiting so the supervisor restarts the daemon on the new build");
                    return ExitCodes.RESTART_REQUESTED;
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
     * The updater to run in the loop, or null when this daemon does not update itself.
     *
     * <p>Says once, at startup, why it is not going to — an operator who turned the setting on wants
     * to find out that this machine has no jar it can install from the log they are already reading,
     * not from an update that silently never arrives.
     *
     * @param config the loaded configuration
     * @param state  the state file holding the ETag and last applied version
     * @param once   whether this is a {@code --once} run, which never applies an update
     * @return the updater, or null
     */
    private static SelfUpdater startUpdater(RemoteConfig config, RemoteState state, boolean once) {
        if (!config.selfUpdate().enabled() || once) {
            return null;
        }
        SelfUpdater updater = new SelfUpdater(config.selfUpdate(), state);
        Optional<String> blocked = updater.canApply();
        if (blocked.isPresent()) {
            log.warn("Self-update is on but cannot run here: {}", blocked.get());
            return null;
        }
        log.info("Checking {} for a new HouseGraph release every {}s",
                config.selfUpdate().repository(), config.selfUpdate().checkSeconds());
        return updater;
    }

    /**
     * One release check, and the install if there is one.
     *
     * <p>Only an actual update is worth an {@code info} line on a machine that logs to a file
     * forever; "still up to date", once an hour, is not. A failed lookup stays at {@code warn}
     * because it is hourly, not per-tick, and because a machine that has quietly stopped being able
     * to update itself is worth noticing.
     *
     * @param updater the configured updater
     * @return true when a new jar was installed and the daemon should restart onto it
     */
    private static boolean checkForUpdate(SelfUpdater updater) {
        SelfUpdater.Decision decision = updater.check();
        switch (decision.action()) {
            case UPDATE -> {
                log.info("{}", decision.message());
                return updater.apply(decision);
            }
            case FAILED -> log.warn("Update check failed: {}", decision.message());
            case UP_TO_DATE -> log.debug("{}", decision.message());
            default -> log.warn("Not updating: {}", decision.message());
        }
        return false;
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
