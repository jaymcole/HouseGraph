package io.github.jaymcole.housegraph.cli.commands;

import io.github.jaymcole.housegraph.catalog.GraphDiff;
import io.github.jaymcole.housegraph.cli.Args;
import io.github.jaymcole.housegraph.cli.Command;
import io.github.jaymcole.housegraph.ui.io.GraphFileIO;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Reports what a proposed save file would change versus one already on disk, without writing
 * anything — a dry run for a harness that generated a candidate graph and wants to see the effect
 * of writing it before it actually does.
 *
 * <p>Reads both files with {@link GraphFileIO#readRoot}, the same entry point {@code validate}
 * uses, and hands the parsed roots to {@link GraphDiff}. Neither file is touched; a caller decides
 * whether to overwrite {@code current.json} with {@code proposed.json} based on the report.
 */
public final class DiffCommand implements Command {

    private final PrintStream out;

    public DiffCommand(PrintStream out) {
        this.out = out;
    }

    @Override
    public String name() {
        return "diff";
    }

    @Override
    public String summary() {
        return "Report what a proposed graph file would change, without writing it";
    }

    @Override
    public String usage() {
        return "  " + name() + " <current.json> <proposed.json> [--json]\n\n"
                + "Compares a proposed save file against one already on disk and reports every\n"
                + "added, removed or changed node, edge, plugin row and camera setting. Neither\n"
                + "file is written to — this only reports what writing <proposed.json> over\n"
                + "<current.json> would change.\n\n"
                + "Nodes are compared by position, the same way the save format addresses them\n"
                + "(an edge names a node by its index): inserting or removing a node anywhere\n"
                + "but the end shifts every later index and shows as a cascade of field changes\n"
                + "rather than one clean move. Data edges, flow edges and plugin rows have no\n"
                + "such positional meaning, so they are matched by content (edges) or id\n"
                + "(plugins) instead — reordering one of those arrays alone reports no change.\n\n"
                + "--json emits a machine-readable report instead of log lines.\n\n"
                + "Exits 0 when the two files are equivalent, 1 when a difference was found.";
    }

    @Override
    public int run(Args args) {
        File currentFile = args.positional(0).map(File::new).orElse(null);
        File proposedFile = args.positional(1).map(File::new).orElse(null);
        boolean json = args.isEnabled("json");
        if (currentFile == null || proposedFile == null) {
            out.println("Usage: housegraph diff <current.json> <proposed.json> [--json]");
            return 2;
        }
        if (!currentFile.isFile()) {
            out.println("No such file: " + currentFile.getAbsolutePath());
            return 2;
        }
        if (!proposedFile.isFile()) {
            out.println("No such file: " + proposedFile.getAbsolutePath());
            return 2;
        }

        try {
            JSONObject current = GraphFileIO.readRoot(currentFile);
            JSONObject proposed = GraphFileIO.readRoot(proposedFile);
            GraphDiff.Report report = GraphDiff.compare(current, proposed);

            if (json) {
                out.println(toJson(currentFile, proposedFile, report).toString(2));
            } else {
                printHuman(currentFile, proposedFile, report);
            }
            return report.isUnchanged() ? 0 : 1;
        } catch (IOException | RuntimeException e) {
            if (json) {
                out.println(new JSONObject().put("error", e.getMessage()).toString(2));
            } else {
                out.println("Could not compare files: " + e.getMessage());
            }
            return 1;
        }
    }

    private void printHuman(File currentFile, File proposedFile, GraphDiff.Report report) {
        if (report.isUnchanged()) {
            out.println(proposedFile.getName() + " would make no change to " + currentFile.getName() + ".");
            return;
        }
        for (GraphDiff.Change change : report.changes()) {
            switch (change.type()) {
                case ADDED -> out.println("+ " + change.pointer() + ": " + describe(change.after()));
                case REMOVED -> out.println("- " + change.pointer() + ": " + describe(change.before()));
                case MODIFIED -> out.println("~ " + change.pointer() + ": "
                        + describe(change.before()) + " -> " + describe(change.after()));
            }
        }
    }

    private static String describe(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private static JSONObject toJson(File currentFile, File proposedFile, GraphDiff.Report report) {
        JSONArray changesJson = new JSONArray();
        for (GraphDiff.Change change : report.changes()) {
            changesJson.put(new JSONObject()
                    .put("type", change.type().name())
                    .put("pointer", change.pointer())
                    .put("before", change.before() == null ? JSONObject.NULL : change.before())
                    .put("after", change.after() == null ? JSONObject.NULL : change.after()));
        }
        return new JSONObject()
                .put("current", currentFile.getName())
                .put("proposed", proposedFile.getName())
                .put("unchanged", report.isUnchanged())
                .put("changes", changesJson);
    }
}
