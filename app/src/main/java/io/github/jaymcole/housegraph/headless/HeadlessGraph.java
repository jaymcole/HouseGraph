package io.github.jaymcole.housegraph.headless;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.loader.GraphLoader;
import io.github.jaymcole.housegraph.loader.LoadedGraph;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.modules.ModuleBinding;
import io.github.jaymcole.housegraph.modules.ModuleDirectory;
import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.sdk.AutoStartable;
import io.github.jaymcole.housegraph.saveformat.ClipboardNode;
import io.github.jaymcole.housegraph.saveformat.GraphFileIO;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens a save file onto a live {@link NodeGraph} with nothing drawing it, and resumes the nodes
 * that were running when the graph was saved.
 *
 * <h2>What one open does</h2>
 * Read the file, report the node libraries it names that are not installed, build the snapshot,
 * hand it to {@link GraphLoader} with no listener — nothing is drawing anything — then resolve every
 * module the graph references, and finally make one pass over the loaded nodes resuming every
 * {@link AutoStartable}. That last step is what turns a loaded graph into a running one: the
 * supervisor opens a graph, it never presses Start.
 *
 * <h2>Modules are bound before anything resumes</h2>
 * A {@code ModuleNode} loads unresolved and refuses to run, so a supervised graph containing one
 * would do nothing at all without {@link ModuleBinding#bindAll}. It runs after every edge is wired,
 * because binding can rebuild the node's ports, and before the resume pass, because a resumed node
 * may pull a value straight through a module.
 *
 * <h2>Missing libraries are reported, not refused</h2>
 * A graph naming a library that is not installed still opens. Its nodes load as {@code MissingNode}
 * placeholders and each missing library is logged with the repository it came from. Refusing would
 * turn one unavailable library into a dead machine, and the rest of the graph is worth running.
 *
 * <h2>One node's resume costs only itself</h2>
 * {@link AutoStartable#autoStartIfWasRunning()} is called inside its own try, because an out-of-tree
 * node library that has not adopted the viewless lifecycle throws here — its start path writes
 * controls that only {@code createNodeContent()} builds, and headlessly nothing built them. Letting
 * that end the pass would cost every node after it. Each failure is logged against the node and the
 * library that owns it, pointing at {@code docs/shared/node-library-rules.md}, and the load
 * continues.
 */
public final class HeadlessGraph {

    private static final Logger log = Log.get(HeadlessGraph.class);

    /**
     * What one {@link #open} produced.
     *
     * @param loaded           the nodes and edges that reached the graph
     * @param missingLibraries libraries the file names that are not installed or are switched off;
     *                         their nodes are placeholders
     * @param resumeFailures   the nodes whose resume threw, in load order
     * @param modules          what the module-binding pass resolved, reshaped and could not find
     */
    public record Opened(LoadedGraph loaded,
                         List<GraphDependencyCheck.RequiredPlugin> missingLibraries,
                         List<ResumeFailure> resumeFailures,
                         ModuleBinding.Result modules) {
    }

    /**
     * One node that threw from {@link AutoStartable#autoStartIfWasRunning()}.
     *
     * @param node    the node's display name
     * @param library the library that owns it, named the way an operator would look it up
     * @param cause   what it threw
     */
    public record ResumeFailure(String node, String library, Throwable cause) {
    }

    private HeadlessGraph() {
    }

    /**
     * Opens {@code file} onto {@code graph} and resumes it.
     *
     * @param file     the save file
     * @param graph    a fresh graph to load into
     * @param registry resolves node types, and says which library each came from
     * @param catalog  what is installed, for both the dependency report and the library names in it
     * @param modules  resolves the modules the graph references; {@link ModuleDirectory#EMPTY} leaves
     *                 every module node unresolved, which is honest for a caller with no library
     * @return what was loaded, what was missing, and what failed to resume
     * @throws IOException      the file could not be read
     * @throws RuntimeException the file is not a save file this build can parse
     */
    public static Opened open(File file, NodeGraph graph, NodeRegistry registry, PluginCatalog catalog,
                              ModuleDirectory modules) throws IOException {
        // Parsed before anything is constructed, so the libraries in use are known before a class
        // from one of them is loaded - the same order App opens a graph in.
        JSONObject root = GraphFileIO.readRoot(file);
        GraphDependencyCheck.DependencyReport report = GraphDependencyCheck.inspect(root, catalog);
        reportMissingLibraries(file, report);

        // No listener: a listener exists so a host can build a view for each node before it joins
        // the graph, and there is no host here.
        LoadedGraph loaded = GraphLoader.load(GraphFileIO.fromRoot(root, registry), ClipboardNode::node, graph);
        log.info("Loaded {}: nodes {}, data edges {}, flow edges {}",
                file.getName(), loaded.nodes().size(), loaded.dataEdges().size(), loaded.flowEdges().size());

        ModuleBinding.Result modulesBound = ModuleBinding.bindAll(loaded.nodes(), modules);
        if (modulesBound.total() > 0) {
            // Worth a line of its own: a module that did not resolve is a part of the graph that will
            // not run, and the per-node warnings alone do not say how much of the graph that is.
            log.info("{} references {} module(s): {} bound, {} rebuilt to a changed interface, {} not found",
                    file.getName(), modulesBound.total(), modulesBound.bound().size(),
                    modulesBound.reshaped().size(), modulesBound.unresolved().size());
        }

        return new Opened(loaded, report.blocking(), resumeRunningNodes(loaded, registry, catalog), modulesBound);
    }

    /**
     * Resumes every {@link AutoStartable} that was running when the graph was saved.
     *
     * <h4>Why this runs after the whole load</h4>
     * Every node is registered (so {@code onActivated()} has run and any resource it publishes is
     * registered) and every edge is wired before the first node is resumed, so a node that pulls an
     * input at Start sees its wiring. That is the same ordering the canvas guarantees; see
     * {@link AutoStartable}.
     */
    private static List<ResumeFailure> resumeRunningNodes(LoadedGraph loaded, NodeRegistry registry,
                                                          PluginCatalog catalog) {
        List<ResumeFailure> failures = new ArrayList<>();
        for (BaseNode node : loaded.nodes()) {
            if (!(node instanceof AutoStartable autoStartable)) {
                continue;
            }
            try {
                autoStartable.autoStartIfWasRunning();
            } catch (RuntimeException | LinkageError failure) {
                // RuntimeException is the unadopted-library case (a null control field, so an NPE).
                // LinkageError is the other shape of the same problem: a library built against a
                // different version of the API, whose start path resolves a class that is no longer
                // there. Both are one node's problem, so neither may end the pass. Nothing broader
                // is caught - an OutOfMemoryError is not this node's fault and not survivable here.
                failures.add(report(node, failure, registry, catalog));
            }
        }
        return failures;
    }

    /** Logs one failed resume in the terms an operator can act on, and records it for the caller. */
    private static ResumeFailure report(BaseNode node, Throwable failure, NodeRegistry registry,
                                        PluginCatalog catalog) {
        String library = libraryOf(node, registry, catalog);
        // The (String, Throwable) overload does no placeholder substitution, hence the concatenation.
        log.error("Node \"" + node.getName() + "\" from " + library + " threw while resuming; it is"
                + " stopped and the rest of the graph is running. A node resumed with no view must keep"
                + " its running state in the node, drive any clock with sdk.NodeTimer, and route every"
                + " control update through BaseNode.present(...) - see docs/shared/node-library-rules.md",
                failure);
        return new ResumeFailure(node.getName(), library, failure);
    }

    /**
     * The library owning {@code node}, as {@code "Discord (housegraph-discord)"} — the human name an
     * operator sees in the library window, plus the id they would type at the CLI. Falls back to the
     * id alone for a library the catalog does not know, which includes {@code core}.
     */
    private static String libraryOf(BaseNode node, NodeRegistry registry, PluginCatalog catalog) {
        String id = registry.pluginIdOf(node.getClass());
        return catalog.byId(id)
                .map(installed -> installed.name() + " (" + id + ")")
                .orElse(id);
    }

    /** One line per library the graph needs and this machine does not have. */
    private static void reportMissingLibraries(File file, GraphDependencyCheck.DependencyReport report) {
        for (GraphDependencyCheck.RequiredPlugin required : report.blocking()) {
            log.warn("{} needs node library {}{}, which is not installed. Its nodes load as"
                            + " placeholders and do nothing; the rest of the graph still runs.",
                    file.getName(), required.label(),
                    required.repository() == null ? "" : " from " + required.repository());
        }
    }
}
