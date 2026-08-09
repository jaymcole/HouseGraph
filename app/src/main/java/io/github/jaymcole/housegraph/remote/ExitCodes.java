package io.github.jaymcole.housegraph.remote;

/**
 * The exit codes a supervised HouseGraph process uses to tell its supervisor what to do next.
 *
 * <p>An exit code is the one channel that works no matter what state the child is in — no socket to
 * keep open, no file to keep flushed, nothing to go stale if the JVM dies mid-sentence. It is also
 * the seam a future automation node uses to ask for a fresh JVM (a node library was updated, say)
 * without needing to know a supervisor exists at all: it exits with {@link #RESTART_REQUESTED} and
 * the supervisor does the rest.
 *
 * <p>The values sit above the usual range so they can't be confused with a JVM crash (1), a shell
 * "command not found" (127), or a signal-terminated process (128+n).
 */
public final class ExitCodes {

    /** Finished, and did not ask to come back. */
    public static final int OK = 0;

    /** Start me again. Used for a deliberate self-restart, not for a failure. */
    public static final int RESTART_REQUESTED = 10;

    /**
     * Something about this graph or its configuration is wrong and will still be wrong next time.
     * The supervisor logs it and stops trying, rather than turning a permanent fault into a
     * restart loop that buries the real error in noise.
     */
    public static final int CONFIGURATION_ERROR = 20;

    private ExitCodes() {
    }

    /** Whether a child that exited with {@code code} should be started again. */
    public static boolean shouldRestart(int code) {
        return code != CONFIGURATION_ERROR;
    }
}
