package io.github.jaymcole.housegraph.headless;

import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.graph.nodes.constants.ConstantFloatNode;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.plugin.PluginLoader;
import io.github.jaymcole.housegraph.remote.ExitCodes;
import io.github.jaymcole.housegraph.saveformat.ClipboardDataEdge;
import io.github.jaymcole.housegraph.saveformat.ClipboardNode;
import io.github.jaymcole.housegraph.saveformat.GraphSnapshot;
import io.github.jaymcole.housegraph.saveformat.SaveFileFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The exit codes the supervisor reads, and the one behaviour that has no other way to be observed:
 * that a loaded graph keeps the process up until something signals it.
 *
 * <p>Each run is on its own thread, because {@code call()} installs the node-library class loader as
 * its thread's context loader and then closes it — doing that to the JUnit thread would leave every
 * later test running under a closed loader.
 */
class HeadlessRunnerTest {

    /** Long enough that a loaded CI machine still gets there; each test returns as soon as it holds. */
    private static final long AWAIT_MILLIS = 10_000;

    /** How long a started run is given to prove it is not going to exit on its own. */
    private static final long STAYS_UP_MILLIS = 250;

    @TempDir
    Path directory;

    @Test
    void aGraphFileThatIsNotThereIsAConfigurationErrorRatherThanARestartLoop() throws Exception {
        assertEquals(ExitCodes.CONFIGURATION_ERROR, exitCodeFor(directory.resolve("absent.json").toFile()),
                "a file that is missing now will be missing on the restart too, and 20 is what stops the supervisor trying");
    }

    @Test
    void aFileThatIsNotAGraphIsAConfigurationErrorToo() throws Exception {
        Path file = directory.resolve("broken.json");
        Files.writeString(file, "{ this is not JSON", StandardCharsets.UTF_8);

        assertEquals(ExitCodes.CONFIGURATION_ERROR, exitCodeFor(file.toFile()));
    }

    @Test
    void namingNoGraphAtAllIsAConfigurationError() {
        assertEquals(ExitCodes.CONFIGURATION_ERROR, HeadlessRunner.run(null),
                "reported without standing up logging or a class loader — there is nothing to run");
    }

    @Test
    void staysUpOnceTheGraphIsLoadedAndExitsCleanWhenSignalled() throws Exception {
        File file = graphFile();
        ShutdownSignal signal = new ShutdownSignal();
        FutureTask<Integer> run = start(file, signal);

        assertThrows(TimeoutException.class, () -> run.get(STAYS_UP_MILLIS, TimeUnit.MILLISECONDS),
                "nothing in a running graph holds the JVM open on its own — the runner has to");

        signal.release();

        assertEquals(ExitCodes.OK, run.get(AWAIT_MILLIS, TimeUnit.MILLISECONDS),
                "a clean shutdown is 0; the supervisor restarting on it is correct, because a graph is meant to stay up");
    }

    // --- helpers ------------------------------------------------------------------

    /** A real save file: a constant wired into an Add, with nothing that starts itself. */
    @SuppressWarnings("unchecked")
    private File graphFile() throws IOException {
        ConstantFloatNode constant = new ConstantFloatNode();
        constant.getOutputs().get(0).setValue(3f);
        GraphSnapshot snapshot = new GraphSnapshot(
                List.of(new ClipboardNode(constant, 0, 0), new ClipboardNode(new AddNode(), 200, 0)),
                List.of(new ClipboardDataEdge(0, 0, 1, 0, List.of())),
                List.of());

        Path file = directory.resolve("graph.json");
        Files.writeString(file, SaveFileFixture.toJson(snapshot, registry()).toString(2), StandardCharsets.UTF_8);
        return file.toFile();
    }

    private static NodeRegistry registry() {
        return new NodeRegistry(List.of(NodeRegistry.ScanRoot.core(HeadlessRunnerTest.class.getClassLoader())));
    }

    /** Runs to completion with the signal already released, and returns the exit code. */
    private int exitCodeFor(File graphFile) throws Exception {
        ShutdownSignal released = new ShutdownSignal();
        released.release();
        return start(graphFile, released).get(AWAIT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private FutureTask<Integer> start(File graphFile, ShutdownSignal signal) {
        PluginCatalog catalog = PluginCatalog.loadFrom(directory.resolve("plugins.json"), directory.resolve("plugins"));
        PluginLoader loader = PluginLoader.from(catalog, HeadlessRunnerTest.class.getClassLoader());
        FutureTask<Integer> run = new FutureTask<Integer>(new HeadlessRunner(graphFile, catalog, loader, signal)::call);
        new Thread(run, "headless-runner-test").start();
        return run;
    }
}
