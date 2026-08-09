package io.github.jaymcole.housegraph.cli;

import io.github.jaymcole.housegraph.cli.commands.CheckCommand;
import io.github.jaymcole.housegraph.cli.commands.DaemonCommand;
import io.github.jaymcole.housegraph.cli.commands.DoctorCommand;
import io.github.jaymcole.housegraph.cli.commands.PluginsCommand;
import io.github.jaymcole.housegraph.cli.commands.SyncCommand;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The command table and the dispatch into it.
 *
 * <h2>Why the CLI shares the app's entry point</h2>
 * {@code Launcher} checks whether the first argument names a command here and, if so, runs it
 * instead of starting JavaFX. One jar, one {@code Main-Class}, no second start script to keep in
 * step — and {@code java -jar app.jar} with no arguments still opens the window exactly as it always
 * has. {@code run} is deliberately <em>not</em> in this table: it is the GUI, so it falls through to
 * {@code Application.launch} and is documented in the usage text as the command it effectively is.
 */
public final class CommandLine {

    /** The name a user types; also what the usage text calls itself. */
    public static final String PROGRAM = "housegraph";

    /**
     * The graph-opening command. Not dispatched here — it needs the JavaFX toolkit, so
     * {@code Launcher} recognises it and lets the normal application launch handle it.
     */
    public static final String RUN_COMMAND = "run";

    /** Arguments that mean "print something and stop", even though they name no command. */
    private static final java.util.Set<String> HELP_FLAGS =
            java.util.Set.of("--help", "-h", "--version", "-v");

    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final PrintStream out;

    public CommandLine() {
        this(System.out);
    }

    /** With an explicit stream, so a test can read what was printed. */
    public CommandLine(PrintStream out) {
        this.out = out;
        register(new DaemonCommand(out));
        register(new SyncCommand(out));
        register(new PluginsCommand(out));
        register(new CheckCommand(out));
        register(new DoctorCommand(out));
    }

    private void register(Command command) {
        commands.put(command.name(), command);
    }

    /**
     * Whether {@code argument} selects a command this table handles.
     *
     * <p>Used by {@code Launcher} to decide between the CLI and the window, so it must be exact:
     * guessing here would turn a mistyped flag into a silently different program.
     *
     * @param argument the first command-line argument
     * @return true when it names a command
     */
    public boolean handles(String argument) {
        return argument != null && commands.containsKey(argument);
    }

    /**
     * Whether this argument list is for the CLI rather than the window.
     *
     * <p>The rule is positional: <b>a bare first word means a command</b>. {@code run} is the sole
     * exception, because opening a graph is the GUI. Anything else bare — including a typo — is
     * handled here so it gets "Unknown command: frobnicate" rather than a window that silently
     * ignores it, or, on a machine with no display, a JavaFX stack trace answering nothing.
     *
     * <p>{@code --help} and {@code --version} count too, for the same reason: someone typing them
     * wants text on their terminal.
     *
     * <p>An argument starting with {@code -} is otherwise left to the app, so
     * {@code java -jar app.jar --graph=x.json} still opens the editor.
     *
     * @param argv the raw arguments
     * @return true when {@link #run} should handle them
     */
    public boolean handlesArguments(String... argv) {
        if (argv.length == 0) {
            return false;
        }
        String first = argv[0];
        if (HELP_FLAGS.contains(first)) {
            return true;
        }
        return !first.startsWith("-") && !RUN_COMMAND.equals(first);
    }

    /** The command named {@code name}, if there is one. */
    public Optional<Command> command(String name) {
        return Optional.ofNullable(commands.get(name));
    }

    /**
     * Runs the command named by {@code argv}.
     *
     * @param argv the raw arguments
     * @return the exit code
     */
    public int run(String... argv) {
        Args args = Args.parse(argv);

        if (args.isSet("version") || (argv.length > 0 && "-v".equals(argv[0]))) {
            out.println(PROGRAM + " " + version());
            return 0;
        }
        if (args.command().isEmpty() || args.isSet("help") || (argv.length > 0 && "-h".equals(argv[0]))) {
            printUsage(args.command());
            return 0;
        }

        Command command = commands.get(args.command());
        if (command == null) {
            out.println("Unknown command: " + args.command());
            printUsage("");
            return 2;
        }
        // --home has to be applied before anything touches AppDirectories, which caches its root on
        // first use. Doing it here rather than in each command means no command can forget to.
        args.option("home").ifPresent(home -> System.setProperty("housegraph.home", home));

        try {
            return command.run(args);
        } catch (RuntimeException e) {
            out.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * The build's version, from the jar manifest.
     *
     * @return the implementation version, or {@code "(development build)"} when running from
     *         exploded classes, where no manifest exists to read one from
     */
    static String version() {
        String version = CommandLine.class.getPackage().getImplementationVersion();
        return version == null ? "(development build)" : version;
    }

    /** Prints the usage listing, or one command's detail when {@code forCommand} names one. */
    void printUsage(String forCommand) {
        Command specific = commands.get(forCommand);
        if (specific != null) {
            out.println("Usage: " + PROGRAM + " " + specific.name());
            out.println();
            out.println("  " + specific.summary());
            if (!specific.usage().isBlank()) {
                out.println();
                out.println(specific.usage());
            }
            return;
        }

        out.println("HouseGraph — a node-graph editor for home automation.");
        out.println();
        out.println("Usage: " + PROGRAM + " [command] [options]");
        out.println();
        out.println("With no command, the graph editor opens on the last graph you had open.");
        out.println();
        out.println("Commands:");
        out.printf("  %-10s %s%n", RUN_COMMAND, "Open the editor on one graph (add --minimized for a daemon)");
        commands.values().forEach(command ->
                out.printf("  %-10s %s%n", command.name(), command.summary()));
        out.println();
        out.println("Global options:");
        out.println("  --home <dir>   Use a different HouseGraph data directory");
        out.println("  --help         Show this, or a command's own usage");
        out.println();
        out.println("Running unattended: docs/architecture/deployment.md");
    }
}
