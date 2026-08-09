package io.github.jaymcole.housegraph.cli.commands;

import io.github.jaymcole.housegraph.cli.Args;
import io.github.jaymcole.housegraph.cli.Command;
import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.ui.io.GraphFileIO;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Reports whether a graph's node libraries are all installed, without opening it.
 *
 * <p>This is {@link GraphDependencyCheck} — the same single pass over the parsed save file the app
 * runs before building a node — made available before deploying rather than after. It reads the
 * file's {@code plugins} table and nothing else: no class is loaded and no node is constructed, so
 * it is safe to point at a graph this machine can't yet run.
 *
 * <p>Exits non-zero when something is missing, so it can gate a deploy from a shell script.
 */
public final class CheckCommand implements Command {

    private final PrintStream out;

    public CheckCommand(PrintStream out) {
        this.out = out;
    }

    @Override
    public String name() {
        return "check";
    }

    @Override
    public String summary() {
        return "Report which node libraries a graph needs and whether they're installed";
    }

    @Override
    public String usage() {
        return "  " + name() + " <graph.json>\n\n"
                + "Exits 0 when every library is installed and enabled, 1 when something is missing.";
    }

    @Override
    public int run(Args args) {
        File file = args.positional(0).map(File::new).orElse(null);
        if (file == null) {
            out.println("Usage: housegraph check <graph.json>");
            return 2;
        }
        if (!file.isFile()) {
            out.println("No such file: " + file.getAbsolutePath());
            return 2;
        }

        GraphDependencyCheck.DependencyReport report;
        try {
            report = GraphDependencyCheck.inspect(GraphFileIO.readRoot(file), PluginCatalog.load());
        } catch (IOException | RuntimeException e) {
            out.println("Could not read " + file + ": " + e.getMessage());
            return 1;
        }

        if (report.isSatisfied() && report.olderThanSaved().isEmpty()) {
            out.println(file.getName() + ": every node library it needs is installed.");
            return 0;
        }

        report.missing().forEach(required -> out.println("missing:   " + describe(required)));
        report.disabled().forEach(required -> out.println("disabled:  " + describe(required)));
        // Advisory only, exactly as in the app: an older library's nodes still resolve, they may
        // just lack a newer feature. It must not fail the check.
        report.olderThanSaved().forEach(required -> out.println("older:     " + describe(required)));

        if (!report.isSatisfied()) {
            out.println();
            out.println("Those nodes would load as placeholders. The graph still opens and nothing is lost.");
            return 1;
        }
        return 0;
    }

    private static String describe(GraphDependencyCheck.RequiredPlugin required) {
        return required.label() + (required.repository() == null
                // A graph saved before libraries recorded their origin, or by a build that had no
                // catalog to hand. Saying so is more use than an unexplained bare id.
                ? "  (no repository recorded — re-save the graph on a machine that has it installed)"
                : "  " + required.repository());
    }
}
