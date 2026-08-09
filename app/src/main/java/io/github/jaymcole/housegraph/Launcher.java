package io.github.jaymcole.housegraph;

import io.github.jaymcole.housegraph.cli.CommandLine;
import javafx.application.Application;

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
        Application.launch(App.class, forApplication(args));
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
