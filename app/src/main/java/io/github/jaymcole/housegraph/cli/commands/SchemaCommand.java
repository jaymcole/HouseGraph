package io.github.jaymcole.housegraph.cli.commands;

import io.github.jaymcole.housegraph.cli.Args;
import io.github.jaymcole.housegraph.cli.Command;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Prints the formal JSON Schema for a HouseGraph JSON format, so a harness can validate a graph
 * structurally — or generate one to a known-valid shape — without reverse-engineering it from
 * {@code docs/engine/save-format.md} or this repository's source.
 *
 * <p>The schemas themselves are bundled resources under {@code schema/} (their source lives at
 * {@code app/src/main/resources/schema/}), not generated: they are hand-maintained the same way the
 * save-format documentation is, and both must be updated together when the format changes.
 */
public final class SchemaCommand implements Command {

    /**
     * {@code graph} serves the version this build <em>writes</em>, so a harness generating a file
     * gets a schema matching what will be read back. Each superseded version stays reachable under
     * its own name, because a tool checking files it did not write still needs them: an older graph
     * is not invalid, it is older.
     */
    private static final Map<String, String> RESOURCES = Map.of(
            "graph", "/schema/graph-save.v4.schema.json",
            "graph-v4", "/schema/graph-save.v4.schema.json",
            "graph-v3", "/schema/graph-save.v3.schema.json",
            "graph-v2", "/schema/graph-save.v2.schema.json",
            "catalog", "/schema/node-catalog.v1.schema.json");

    private final PrintStream out;

    public SchemaCommand(PrintStream out) {
        this.out = out;
    }

    @Override
    public String name() {
        return "schema";
    }

    @Override
    public String summary() {
        return "Print the JSON Schema for the save format or the node catalog";
    }

    @Override
    public String usage() {
        return "  schema [graph|graph-v4|graph-v3|graph-v2|catalog]\n\n"
                + "graph (default) is the JSON Schema for the save-file format this build writes\n"
                + "(version 4). graph-v4, graph-v3 and graph-v2 name a specific format version, for\n"
                + "checking a file this build did not write. catalog is the schema for\n"
                + "`nodes list --json`'s output.\n\n"
                + "Redirect it to a file to hand to any standard JSON Schema validator:\n"
                + "  housegraph schema > graph.schema.json";
    }

    @Override
    public int run(Args args) {
        String which = args.positional(0).orElse("graph");
        String resource = RESOURCES.get(which);
        if (resource == null) {
            out.println("Unknown schema: " + which + " (expected graph, graph-v4, graph-v3, graph-v2 or catalog)");
            out.println(usage());
            return 2;
        }
        try (InputStream in = SchemaCommand.class.getResourceAsStream(resource)) {
            if (in == null) {
                out.println("Schema resource missing from this build: " + resource);
                return 1;
            }
            out.println(new String(in.readAllBytes(), StandardCharsets.UTF_8).strip());
            return 0;
        } catch (IOException e) {
            out.println("Could not read the schema: " + e.getMessage());
            return 1;
        }
    }
}
