package io.github.jaymcole.housegraph.cli.commands;

import io.github.jaymcole.housegraph.cli.CommandLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code diff}'s argument handling and end-to-end file comparison. {@code diff} needs no
 * node registry or plugin catalog — it never instantiates a node — so, unlike {@code validate},
 * it can be exercised against real files here.
 */
class DiffCommandTest {

    @TempDir
    Path tempDir;

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final CommandLine commandLine = new CommandLine(new PrintStream(captured, true, StandardCharsets.UTF_8));

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    private Path writeGraph(String name, String json) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, json);
        return file;
    }

    private static final String EMPTY_GRAPH =
            "{\"version\":2,\"nodes\":[],\"dataEdges\":[],\"flowEdges\":[]}";

    @Test
    void withNoFilesReportsUsage() {
        assertNotEquals(0, commandLine.run("diff"));

        assertTrue(output().contains("Usage: housegraph diff"));
    }

    @Test
    void withAMissingFileFailsCleanly() throws IOException {
        Path current = writeGraph("current.json", EMPTY_GRAPH);

        assertNotEquals(0, commandLine.run("diff", current.toString(), "/no/such/proposed.json"));

        assertTrue(output().contains("No such file"));
    }

    @Test
    void identicalFilesExitZero() throws IOException {
        Path current = writeGraph("current.json", EMPTY_GRAPH);
        Path proposed = writeGraph("proposed.json", EMPTY_GRAPH);

        assertEquals(0, commandLine.run("diff", current.toString(), proposed.toString()));

        assertTrue(output().contains("would make no change"));
    }

    @Test
    void writesNothingToEitherFile() throws IOException {
        Path current = writeGraph("current.json", EMPTY_GRAPH);
        Path proposed = writeGraph("proposed.json",
                "{\"version\":2,\"nodes\":[{\"type\":\"AddNode\",\"x\":1,\"y\":2}],\"dataEdges\":[],\"flowEdges\":[]}");

        commandLine.run("diff", current.toString(), proposed.toString());

        assertEquals(EMPTY_GRAPH, Files.readString(current));
    }

    @Test
    void differingFilesExitOneAndReportThePointer() throws IOException {
        Path current = writeGraph("current.json", EMPTY_GRAPH);
        Path proposed = writeGraph("proposed.json",
                "{\"version\":2,\"nodes\":[{\"type\":\"AddNode\",\"x\":1,\"y\":2}],\"dataEdges\":[],\"flowEdges\":[]}");

        assertEquals(1, commandLine.run("diff", current.toString(), proposed.toString()));

        assertTrue(output().contains("/nodes/0"));
    }

    @Test
    void jsonReportsMachineReadableChanges() throws IOException {
        Path current = writeGraph("current.json", EMPTY_GRAPH);
        Path proposed = writeGraph("proposed.json",
                "{\"version\":2,\"nodes\":[{\"type\":\"AddNode\",\"x\":1,\"y\":2}],\"dataEdges\":[],\"flowEdges\":[]}");

        assertEquals(1, commandLine.run("diff", current.toString(), proposed.toString(), "--json"));

        assertTrue(output().contains("\"unchanged\": false"));
        assertTrue(output().contains("\"pointer\": \"/nodes/0\""));
    }

    @Test
    void helpDescribesTheCommand() {
        assertEquals(0, commandLine.run("diff", "--help"));

        assertTrue(output().contains("without writing"));
        assertTrue(output().contains("content"));
    }
}
