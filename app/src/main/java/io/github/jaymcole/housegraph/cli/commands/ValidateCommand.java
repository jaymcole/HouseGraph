package io.github.jaymcole.housegraph.cli.commands;

import io.github.jaymcole.housegraph.catalog.GraphStructureValidator;
import io.github.jaymcole.housegraph.cli.Args;
import io.github.jaymcole.housegraph.cli.Command;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.modules.ModuleLibrary;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.plugin.PluginLoader;
import io.github.jaymcole.housegraph.saveformat.GraphFileIO;
import io.github.jaymcole.housegraph.storage.AppDirectories;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs {@link GraphStructureValidator} against a save file from the terminal: the structural checks
 * a GUI session would otherwise surface one dropped edge or one runtime crash at a time — a dangling
 * node reference, an edge whose named port no longer exists, an incompatible data connection, a data
 * cycle — all in one pass, with no window and no library actually run.
 *
 * <p>Like {@code nodes check}, this loads every enabled node library to read each node's real ports,
 * so it answers a different question than {@code check}: point this at a graph only once its
 * libraries are installed ({@code check} is what answers that first).
 *
 * <p>This is also where the module-reference cycle check gets its teeth. {@code
 * GraphStructureValidator} does no I/O, so following a reference into another graph's file is a
 * capability handed in rather than taken — and a command-line tool already reading a file from disk
 * is where doing so is uncontroversial. The resolver searches
 * {@link AppDirectories#modules()} plus the directory the graph being validated sits in, so a module
 * kept beside its consumer is found without being published first.
 */
public final class ValidateCommand implements Command {

    private final PrintStream out;

    public ValidateCommand(PrintStream out) {
        this.out = out;
    }

    @Override
    public String name() {
        return "validate";
    }

    @Override
    public String summary() {
        return "Check a graph for dangling edges, type mismatches and data cycles";
    }

    @Override
    public String usage() {
        return "  " + name() + " <graph.json> [--json]\n\n"
                + "Reports a dangling node/port reference, a data edge whose types are\n"
                + "incompatible, more than one data edge feeding the same input, a cycle in the\n"
                + "data graph (which would fail at run time, not on load), a module boundary\n"
                + "marker nothing could bind to, and a module that references itself. Each\n"
                + "finding carries a JSON Pointer into the save file naming exactly what to fix.\n\n"
                + "--json emits a machine-readable report instead of log lines.\n\n"
                + "Exits 0 when nothing is wrong, 1 when a finding was reported.";
    }

    @Override
    public int run(Args args) {
        File file = args.positional(0).map(File::new).orElse(null);
        if (file == null) {
            out.println("Usage: housegraph validate <graph.json> [--json]");
            return 2;
        }
        if (!file.isFile()) {
            out.println("No such file: " + file.getAbsolutePath());
            return 2;
        }

        boolean json = args.isEnabled("json");
        PluginCatalog plugins = PluginCatalog.load();
        try (PluginLoader loader = PluginLoader.from(plugins, getClass().getClassLoader())) {
            NodeRegistry registry = new NodeRegistry(loader.scanRoots());
            JSONObject root = GraphFileIO.readRoot(file);
            ModuleLibrary modules = ModuleLibrary.over(searchRoots(file), registry);
            GraphStructureValidator.Report report =
                    GraphStructureValidator.inspect(root, registry, modules::rootOf);

            if (json) {
                out.println(toJson(file, report).toString(2));
            } else {
                printHuman(file, report);
            }
            return report.isValid() ? 0 : 1;
        } catch (IOException | RuntimeException e) {
            if (json) {
                out.println(new JSONObject().put("file", file.getName()).put("error", e.getMessage()).toString(2));
            } else {
                out.println("Could not read " + file + ": " + e.getMessage());
            }
            return 1;
        }
    }

    /**
     * Where to look for a referenced module: the machine's module directory, and the directory the
     * graph itself is in. The second is what makes a repository of graphs — where a module and its
     * consumer are checked into the same folder — validate without anything being installed first.
     */
    private static List<Path> searchRoots(File graph) {
        Path beside = graph.getAbsoluteFile().toPath().getParent();
        return beside == null ? List.of(AppDirectories.get().modules())
                : List.of(AppDirectories.get().modules(), beside);
    }

    private void printHuman(File file, GraphStructureValidator.Report report) {
        if (report.isValid()) {
            out.println(file.getName() + ": no structural problems found.");
            return;
        }
        for (GraphStructureValidator.Finding finding : report.findings()) {
            out.println(finding.severity() + " [" + finding.code() + "] " + finding.pointer() + ": " + finding.message());
            for (String related : finding.relatedPointers()) {
                out.println("    also: " + related);
            }
        }
    }

    private static JSONObject toJson(File file, GraphStructureValidator.Report report) {
        JSONArray findingsJson = new JSONArray();
        for (GraphStructureValidator.Finding finding : report.findings()) {
            findingsJson.put(new JSONObject()
                    .put("code", finding.code())
                    .put("severity", finding.severity().name())
                    .put("message", finding.message())
                    .put("pointer", finding.pointer())
                    .put("relatedPointers", new JSONArray(finding.relatedPointers())));
        }
        return new JSONObject()
                .put("file", file.getName())
                .put("valid", report.isValid())
                .put("findings", findingsJson);
    }
}
