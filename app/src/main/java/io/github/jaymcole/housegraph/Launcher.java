package io.github.jaymcole.housegraph;

import io.github.jaymcole.housegraph.cli.CommandLine;
import io.github.jaymcole.housegraph.headless.HeadlessRunner;
import javafx.application.Application;

import java.io.File;
import java.util.Optional;

/**
 * Plain (non-JavaFX) entry point.
 * <p>
 * Launching JavaFX from a {@code main} that lives in a class which does not
 * itself extend {@link Application} avoids the "JavaFX runtime components are
 * missing" error when the app is started from a plain classpath jar. That is why
 * {@code main} stays here rather than moving into {@link App}.
 *
 * <h2>The command-line fork</h2>
 * The first argument decides which program this is. When it names a headless command — see
 * {@link CommandLine} — that command runs and the JVM exits with its code, never touching JavaFX.
 * Anything else, including no arguments at all, launches the window exactly as before; {@code run}
 * falls through on purpose, because opening a graph <em>is</em> the GUI, and {@code App} picks up
 * its {@code --graph} argument from there.
 * <p>
 * {@code run} forks once more, on {@link CommandLine#HEADLESS_FLAG}: {@code run --headless <graph>}
 * runs that graph with no window and exits with the runner's code, while {@code run <graph>} opens
 * the editor exactly as it always has. The fork is here rather than in the command table because
 * neither form behaves like a command — one opens a window, the other stays up until the process is
 * signalled — and because the graph argument is found by the same rule for both.
 * <p>
 * Sharing one entry point keeps the shaded jar to a single {@code Main-Class} and means the CLI can
 * never drift out of step with the app it drives.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine();
        if (commandLine.handlesArguments(args)) {
            System.exit(commandLine.run(args));
        }
        if (isHeadlessRun(args)) {
            // Returns only once the process has been signalled and the graph torn down, so there
            // is nothing to do afterwards but exit with what it says. No toolkit is started here.
            System.exit(HeadlessRunner.run(graphArgument(args).map(File::new).orElse(null)));
        }
        Application.launch(App.class, forApplication(args));
    }

    /**
     * Whether these arguments ask for {@code run --headless <graph>} rather than the editor.
     *
     * <p>Scanned rather than parsed with {@link io.github.jaymcole.housegraph.cli.Args}, because
     * that parser reads {@code --headless porch.json} as a flag <em>with a value</em> and would
     * swallow the graph. The rule here is the same one {@link #forApplication} uses, so a graph is
     * found in the same place whichever way the run goes.
     *
     * @param args the raw arguments
     * @return true when {@code HeadlessRunner} should have them
     */
    static boolean isHeadlessRun(String... args) {
        if (args.length == 0 || !CommandLine.RUN_COMMAND.equals(args[0])) {
            return false;
        }
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--" + CommandLine.HEADLESS_FLAG)
                    || args[i].equals("--" + CommandLine.HEADLESS_FLAG + "=true")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The graph named on a {@code run} command line: the first bare argument, or the value of an
     * explicit {@code --graph=}. Empty when none was given, which the runner reports as a
     * configuration error rather than guessing at a last-opened file the way the editor does.
     *
     * @param args the raw arguments
     * @return the path as it was written
     */
    static Optional<String> graphArgument(String... args) {
        String named = "--" + App.GRAPH_PARAMETER + "=";
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith(named)) {
                return nonBlank(args[i].substring(named.length()));
            }
            if (!args[i].startsWith("-")) {
                return nonBlank(args[i]);
            }
        }
        return Optional.empty();
    }

    /** Trimmed, or empty when there was nothing but whitespace — as {@code App} reads {@code --graph}. */
    private static Optional<String> nonBlank(String path) {
        return path.isBlank() ? Optional.empty() : Optional.of(path.trim());
    }

    /**
     * Rewrites {@code run <graph> [options]} into the form JavaFX will hand to {@link App}.
     *
     * <p>{@code Application.Parameters.getNamed()} only recognises {@code --name=value}, so
     * {@code run foo.json} — the natural way to type it, and what the supervisor generates — would
     * otherwise arrive as an unnamed argument and be ignored. Translating here keeps the ergonomics
     * at the command line and lets {@code App} read one well-defined named parameter.
     *
     * @param args the raw arguments
     * @return the arguments to launch with; unchanged unless the first is {@code run}
     */
    static String[] forApplication(String[] args) {
        if (args.length == 0 || !CommandLine.RUN_COMMAND.equals(args[0])) {
            return args;
        }
        String[] rewritten = new String[args.length - 1];
        boolean graphNamed = false;
        for (int i = 1; i < args.length; i++) {
            // Only the first bare (non-option) argument becomes the graph; anything already written
            // as --graph=... is left exactly as the caller wrote it.
            if (!graphNamed && !args[i].startsWith("-")) {
                rewritten[i - 1] = "--" + App.GRAPH_PARAMETER + "=" + args[i];
                graphNamed = true;
            } else {
                rewritten[i - 1] = args[i];
            }
        }
        return rewritten;
    }
}
