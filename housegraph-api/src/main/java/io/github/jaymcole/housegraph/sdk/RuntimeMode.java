package io.github.jaymcole.housegraph.sdk;

/**
 * Whether this JVM is a graph the remote daemon's supervisor started, as opposed to one a
 * person opened by hand (double-clicking the jar, {@code housegraph run <graph>} typed at a
 * terminal, or the editor's own Load button).
 * <p>
 * The supervisor's child launcher sets the {@code housegraph.daemon} system property on every
 * process it spawns (see {@code GraphProcess.defaultLauncher} in the app module); nothing else
 * sets it, so a graph opened any other way always reads {@link #isDaemon()} as false.
 *
 * <h2>Why a node would care</h2>
 * {@code AutoStartable} resumes a node only if it was left running at save time, which is the
 * right default for something a person starts and stops by hand. It is the wrong shape for a
 * node that binds a port or opens a connection a desktop editor and a deployed server must never
 * hold at the same time on the same LAN: leaving it "running" so it survives a save would make
 * editing and deploying fight each other. Such a node instead checks {@link #isDaemon()} from
 * {@code AutoStartable#autoStartIfWasRunning()} and calls {@code BaseNode#execute()}
 * unconditionally — no saved flag, so it never starts while the graph is merely open for
 * editing, and always starts when the supervisor opens it. See
 * {@code docs/nodes/state-and-startup.md}.
 */
public final class RuntimeMode {

    private static final String DAEMON_PROPERTY = "housegraph.daemon";

    private RuntimeMode() {
    }

    /**
     * @return true when this JVM was launched by the remote daemon's supervisor to run one graph
     *         unattended, false for a graph opened by a person
     */
    public static boolean isDaemon() {
        return Boolean.getBoolean(DAEMON_PROPERTY);
    }
}
