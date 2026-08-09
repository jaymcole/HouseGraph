package io.github.jaymcole.housegraph.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A parsed command line: the subcommand, its positional arguments, and its flags.
 *
 * <p>Hand-rolled rather than a library, for the same reason {@code GitHubReleases} uses the JDK's
 * own {@code HttpClient}: this project has kept to two third-party dependencies, and none of what a
 * parsing library offers is needed by six subcommands. Being pure also means the whole surface is
 * unit-testable without running anything.
 *
 * <p>Accepted forms, chosen to match what people type without thinking:
 * <ul>
 *   <li>{@code --name=value} and {@code --name value} — both set {@code name}</li>
 *   <li>{@code --flag} — sets {@code flag} to {@code "true"}, so {@link #isSet} works on it</li>
 *   <li>anything else — a positional argument, in order</li>
 * </ul>
 * A {@code --name} immediately followed by another {@code --something} is treated as a flag, not as
 * a value: {@code --minimized --graph x.json} means what it looks like.
 */
public final class Args {

    private final String command;
    private final List<String> positionals;
    private final Map<String, String> options;

    private Args(String command, List<String> positionals, Map<String, String> options) {
        this.command = command;
        this.positionals = List.copyOf(positionals);
        this.options = Map.copyOf(options);
    }

    /**
     * Parses a raw argument array.
     *
     * @param argv the arguments, with the subcommand first
     * @return the parsed form; the command is {@code ""} when {@code argv} is empty
     */
    public static Args parse(String... argv) {
        String command = "";
        List<String> positionals = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();

        int index = 0;
        if (argv.length > 0 && !argv[0].startsWith("-")) {
            command = argv[0];
            index = 1;
        }

        for (; index < argv.length; index++) {
            String argument = argv[index];
            if (!argument.startsWith("--")) {
                positionals.add(argument);
                continue;
            }
            String body = argument.substring(2);
            int equals = body.indexOf('=');
            if (equals >= 0) {
                options.put(body.substring(0, equals), body.substring(equals + 1));
                continue;
            }
            // Only consume the next token as a value when it isn't itself an option; otherwise a
            // bare flag would silently swallow the argument that follows it.
            if (index + 1 < argv.length && !argv[index + 1].startsWith("--")) {
                options.put(body, argv[++index]);
            } else {
                options.put(body, "true");
            }
        }
        return new Args(command, positionals, options);
    }

    /** The subcommand, or {@code ""} when none was given. */
    public String command() {
        return command;
    }

    /** The positional arguments, in order. */
    public List<String> positionals() {
        return positionals;
    }

    /** The positional at {@code index}, if there is one. */
    public Optional<String> positional(int index) {
        return index < positionals.size() ? Optional.of(positionals.get(index)) : Optional.empty();
    }

    /** The value of {@code --name}, if it was given. */
    public Optional<String> option(String name) {
        String value = options.get(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /** Whether {@code --name} was given at all, in flag or {@code =value} form. */
    public boolean isSet(String name) {
        return options.containsKey(name);
    }

    /** Whether {@code --name} was given and isn't explicitly false. */
    public boolean isEnabled(String name) {
        return options.containsKey(name) && !"false".equalsIgnoreCase(options.get(name));
    }

    /** Every option, for a caller that wants to report unrecognised ones. */
    public Map<String, String> options() {
        return options;
    }
}
