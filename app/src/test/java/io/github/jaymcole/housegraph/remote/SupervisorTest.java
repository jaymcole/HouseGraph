package io.github.jaymcole.housegraph.remote;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers restart, backoff and the exit-code contract with a fake launcher and a fake clock.
 *
 * <p>Spawning real JVMs would make this slow, flaky and mostly a test of {@code ProcessBuilder}. The
 * logic actually worth pinning down is the decision-making — when to restart, how long to wait, when
 * to give up — and none of it needs a real process. The injected clock is what lets a sixty-second
 * backoff be asserted in microseconds.
 */
class SupervisorTest {

    /** A {@link Process} whose liveness and exit code the test drives directly. */
    private static final class FakeProcess extends Process {
        private boolean alive = true;
        private int exitCode;

        void exitWith(int code) {
            this.exitCode = code;
            this.alive = false;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException();
            }
            return exitCode;
        }

        @Override
        public int waitFor() {
            alive = false;
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            alive = false;
            return true;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }
    }

    /** Records what was launched and hands back a process the test controls. */
    private static final class FakeLauncher implements GraphProcess.Launcher {
        private final List<Path> launched = new ArrayList<>();
        private final List<FakeProcess> processes = new ArrayList<>();
        private IOException failWith;

        @Override
        public Process launch(Path graph) throws IOException {
            if (failWith != null) {
                throw failWith;
            }
            launched.add(graph);
            FakeProcess process = new FakeProcess();
            processes.add(process);
            return process;
        }

        FakeProcess latest() {
            return processes.get(processes.size() - 1);
        }
    }

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final FakeLauncher launcher = new FakeLauncher();
    private final Supervisor supervisor = new Supervisor(launcher, now::get);

    private static final Path PORCH = Path.of("/repo/graphs/porch.json");
    private static final Path HALL = Path.of("/repo/graphs/hall.json");

    private void advance(java.time.Duration duration) {
        now.addAndGet(duration.toMillis());
    }

    @Test
    void startsEveryGraphItIsGiven() {
        supervisor.setGraphs(List.of(PORCH, HALL));
        supervisor.tick();

        assertEquals(List.of(PORCH, HALL), launcher.launched);
        assertTrue(supervisor.isRunning(PORCH));
    }

    @Test
    void doesNotRestartAGraphThatIsStillRunning() {
        supervisor.setGraphs(List.of(PORCH));
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();

        assertEquals(1, launcher.launched.size());
    }

    @Test
    void stopsAGraphRemovedFromTheSet() {
        supervisor.setGraphs(List.of(PORCH, HALL));
        supervisor.tick();

        supervisor.setGraphs(List.of(PORCH));

        assertEquals(List.of(PORCH), supervisor.graphs());
        assertTrue(supervisor.isRunning(PORCH), "the graph that stayed is untouched");
    }

    @Test
    void restartsAGraphThatExitedAfterRunningNormally() {
        supervisor.setGraphs(List.of(PORCH));
        supervisor.tick();
        advance(Supervisor.HEALTHY_AFTER.plusSeconds(1));
        launcher.latest().exitWith(1);

        supervisor.tick();

        assertEquals(2, launcher.launched.size(), "a long-running graph that dies is retried at once");
    }

    @Test
    void backsOffWhenAGraphKeepsFailingImmediately() {
        // Without this the supervisor would restart as fast as a JVM can start, pinning a core and
        // burying the real error under thousands of identical lines.
        supervisor.setGraphs(List.of(PORCH));
        supervisor.tick();
        launcher.latest().exitWith(1);

        supervisor.tick();
        assertEquals(1, launcher.launched.size(), "the retry is delayed, not immediate");

        advance(Supervisor.INITIAL_BACKOFF);
        supervisor.tick();
        assertEquals(2, launcher.launched.size());

        launcher.latest().exitWith(1);
        supervisor.tick();
        advance(Supervisor.INITIAL_BACKOFF);
        supervisor.tick();
        assertEquals(2, launcher.launched.size(), "the second wait is longer than the first");

        advance(Supervisor.INITIAL_BACKOFF);
        supervisor.tick();
        assertEquals(3, launcher.launched.size());
    }

    @Test
    void backoffIsCapped() {
        supervisor.setGraphs(List.of(PORCH));
        for (int attempt = 0; attempt < 20; attempt++) {
            supervisor.tick();
            if (supervisor.isRunning(PORCH)) {
                launcher.latest().exitWith(1);
            }
            advance(Supervisor.MAXIMUM_BACKOFF);
        }
        int launchesSoFar = launcher.launched.size();

        advance(Supervisor.MAXIMUM_BACKOFF);
        supervisor.tick();

        assertEquals(launchesSoFar + 1, launcher.launched.size(),
                "however long it has been failing, one maximum-backoff wait is always enough");
    }

    @Test
    void aRestartRequestIsHonouredImmediately() {
        // The seam a node uses to ask for a fresh JVM. It is not a failure, so it must not be
        // slowed down by the backoff meant for crashes.
        supervisor.setGraphs(List.of(PORCH));
        supervisor.tick();
        launcher.latest().exitWith(ExitCodes.RESTART_REQUESTED);

        supervisor.tick();

        assertEquals(2, launcher.launched.size());
    }

    @Test
    void aConfigurationErrorStopsTheGraphBeingRetried() {
        supervisor.setGraphs(List.of(PORCH));
        supervisor.tick();
        launcher.latest().exitWith(ExitCodes.CONFIGURATION_ERROR);

        supervisor.tick();
        advance(Supervisor.MAXIMUM_BACKOFF.multipliedBy(10));
        supervisor.tick();

        assertEquals(1, launcher.launched.size(), "a permanent fault must not become a restart loop");
        assertEquals(List.of(PORCH), supervisor.abandoned());
    }

    @Test
    void restartAllRevivesAnAbandonedGraph() {
        // A new commit may be exactly the fix for whatever was misconfigured, so a repository change
        // has to clear the abandonment as well as the backoff.
        supervisor.setGraphs(List.of(PORCH));
        supervisor.tick();
        launcher.latest().exitWith(ExitCodes.CONFIGURATION_ERROR);
        supervisor.tick();
        assertFalse(supervisor.abandoned().isEmpty());

        supervisor.restartAll();
        supervisor.tick();

        assertEquals(2, launcher.launched.size());
        assertTrue(supervisor.abandoned().isEmpty());
    }

    @Test
    void aLauncherFailureBacksOffRatherThanSpinning() {
        launcher.failWith = new IOException("no java binary");
        supervisor.setGraphs(List.of(PORCH));

        supervisor.tick();
        supervisor.tick();

        assertTrue(launcher.launched.isEmpty());
        assertFalse(supervisor.isRunning(PORCH));
    }
}
