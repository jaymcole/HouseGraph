package io.github.jaymcole.housegraph.cli;

/**
 * One subcommand.
 *
 * <p>Returns an exit code rather than throwing or calling {@code System.exit}, so a command stays a
 * plain function that a test can call and assert on — the same reason the {@code plugin} package
 * keeps its work out of dialogs.
 */
public interface Command {

    /** The word that selects this command. */
    String name();

    /** One line for the usage listing. */
    String summary();

    /** Usage detail shown by {@code --help} on this command; may be multiple lines. */
    default String usage() {
        return "";
    }

    /**
     * Runs the command.
     *
     * @param args the parsed command line
     * @return the process exit code; 0 for success
     */
    int run(Args args);
}
