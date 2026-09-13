package io.github.jaymcole.housegraph.remote;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers stopping a supervised child, against <b>real</b> processes.
 *
 * <p>Unlike {@code SupervisorTest}, which fakes processes because the decisions are what matter
 * there, what matters here is the part no fake can tell the truth about: whether a kill has actually
 * landed, and what happens to a process the child spawned. Both are properties of the operating
 * system, so the test asks the operating system.
 *
 * <p>Unix only — it builds its process tree with {@code sh}. The behaviour under test is not
 * platform-specific, but a portable way to spawn a grandchild is more machinery than the coverage is
 * worth.
 */
class GraphProcessTest {

    private static boolean isUnix() {
        return !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Test
    void stoppingAGraphAlsoStopsTheSubprocessItSpawned() throws Exception {
        assumeTrue(isUnix(), "builds its process tree with sh");

        // A shell that spawns a background sleep and prints its pid, then waits: the same shape as a
        // node that starts a web server and keeps running. Killing the shell alone leaves the sleep
        // reparented and very much alive, holding whatever it holds.
        Process parent = new ProcessBuilder("sh", "-c", "sleep 60 & echo $!; wait")
                .redirectErrorStream(true)
                .start();
        long grandchildPid;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(parent.getInputStream(), StandardCharsets.UTF_8))) {
            grandchildPid = Long.parseLong(reader.readLine().trim());
        }
        Optional<ProcessHandle> grandchild = ProcessHandle.of(grandchildPid);
        assumeTrue(grandchild.isPresent() && grandchild.get().isAlive(), "the subprocess started");

        try {
            assertTrue(GraphProcess.stop(parent, 5), "the shell should stop on a signal");

            assertFalse(parent.isAlive());
            assertTrue(grandchild.get().onExit().get(10, TimeUnit.SECONDS) != null,
                    "the subprocess has to go with the graph, or it keeps its port and the "
                            + "replacement graph cannot bind");
            assertFalse(grandchild.get().isAlive());
        } finally {
            grandchild.ifPresent(ProcessHandle::destroyForcibly);
            parent.destroyForcibly();
        }
    }

    @Test
    void stoppingReportsSuccessOnlyOnceTheProcessIsReallyGone() throws Exception {
        assumeTrue(isUnix(), "uses sh to hold a process open");

        Process process = new ProcessBuilder("sh", "-c", "sleep 60").start();
        try {
            assertTrue(GraphProcess.stop(process, 5));
            assertFalse(process.isAlive(), "stop() must not return before the process has exited");
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void stoppingSomethingAlreadyGoneIsFineAndImmediate() throws Exception {
        assumeTrue(isUnix(), "uses true(1)");

        Process process = new ProcessBuilder("sh", "-c", "exit 0").start();
        process.waitFor();

        assertTrue(GraphProcess.stop(process, 5));
    }
}
