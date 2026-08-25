package io.github.jaymcole.housegraph.cli.commands;

import io.github.jaymcole.housegraph.catalog.NodeCatalog;
import io.github.jaymcole.housegraph.catalog.SchemaDriftCheck;
import io.github.jaymcole.housegraph.cli.Args;
import io.github.jaymcole.housegraph.cli.Command;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.plugin.PluginLoader;
import io.github.jaymcole.housegraph.ui.io.GraphFileIO;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

/**
 * Surfaces the node catalog and per-node schema drift to the terminal — the two pieces of
 * information an external harness needs and that otherwise exist only as JavaFX menu contents: what
 * node types are installed and what they look like ({@link NodeCatalog}), and whether a specific
 * graph still matches what is installed now ({@link SchemaDriftCheck}).
 *
 * <p>Both actions load every enabled node library, exactly as opening the editor would — unlike
 * {@code check}, which deliberately never loads a class. Point this at a graph only once its
 * libraries are actually installed; {@code check} is what answers that question first.
 */
public final class NodesCommand implements Command {

    private final PrintStream out;

    public NodesCommand(PrintStream out) {
        this.out = out;
    }

    @Override
    public String name() {
        return "nodes";
    }

    @Override
    public String summary() {
        return "List installed node types, or check a graph's nodes for schema drift";
    }

    @Override
    public String usage() {
        return "  nodes list [--json]\n"
                + "  nodes check <graph.json>\n\n"
                + "list prints every installed node type; --json emits the full versioned catalog\n"
                + "(catalogVersion " + NodeCatalog.CATALOG_VERSION + "): each type's id, category, kind, owning\n"
                + "library, and typed inputs/outputs/flow ports.\n\n"
                + "check compares a save file's per-node signatures against what is currently\n"
                + "installed and reports any node whose ports, flow ports or default params have\n"
                + "changed since the graph was saved. Exits non-zero when something has drifted.";
    }

    @Override
    public int run(Args args) {
        String action = args.positional(0).orElse("list");
        return switch (action) {
            case "list" -> list(args);
            case "check" -> check(args);
            default -> {
                out.println("Unknown nodes action: " + action);
                out.println(usage());
                yield 2;
            }
        };
    }

    private int list(Args args) {
        PluginCatalog plugins = PluginCatalog.load();
        try (PluginLoader loader = PluginLoader.from(plugins, getClass().getClassLoader())) {
            NodeRegistry registry = new NodeRegistry(loader.scanRoots());
            List<NodeCatalog.Entry> entries = NodeCatalog.discover(registry, plugins);
            if (args.isEnabled("json")) {
                out.println(NodeCatalog.toJson(entries).toString(2));
                return 0;
            }
            for (NodeCatalog.Entry entry : entries) {
                out.printf("%-28s %-24s %-9s %s%n",
                        entry.type(),
                        entry.category().isBlank() ? "(root)" : entry.category(),
                        entry.kind() == null ? "-" : entry.kind(),
                        entry.pluginId());
            }
            return 0;
        }
    }

    private int check(Args args) {
        File file = args.positional(1).map(File::new).orElse(null);
        if (file == null) {
            out.println("Usage: housegraph nodes check <graph.json>");
            return 2;
        }
        if (!file.isFile()) {
            out.println("No such file: " + file.getAbsolutePath());
            return 2;
        }

        PluginCatalog plugins = PluginCatalog.load();
        try (PluginLoader loader = PluginLoader.from(plugins, getClass().getClassLoader())) {
            NodeRegistry registry = new NodeRegistry(loader.scanRoots());
            JSONObject root = GraphFileIO.readRoot(file);
            List<SchemaDriftCheck.Drift> drifted = SchemaDriftCheck.inspect(root, registry);
            if (drifted.isEmpty()) {
                out.println(file.getName() + ": every resolvable node still matches what this graph was saved against.");
                return 0;
            }
            for (SchemaDriftCheck.Drift drift : drifted) {
                out.println("drifted:   " + drift.type() + (drift.pluginId() == null ? "" : " (" + drift.pluginId() + ")")
                        + "  saved " + drift.savedSignature() + " -> now " + drift.currentSignature());
            }
            out.println();
            out.println("A node's inputs, outputs, flow ports or default params changed since this graph");
            out.println("was saved. Saved values and edges still bind by name where they can, but check");
            out.println("the node before running this graph unattended.");
            return 1;
        } catch (IOException | RuntimeException e) {
            out.println("Could not read " + file + ": " + e.getMessage());
            return 1;
        }
    }
}
