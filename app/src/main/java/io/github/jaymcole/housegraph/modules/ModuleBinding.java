package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleNode;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves every {@link ModuleNode} in a just-loaded graph against a {@link ModuleDirectory} — the
 * one pass that turns a save file's module references into modules that can actually run.
 *
 * <h2>Why a load needs this at all</h2>
 * Loading a graph does no I/O beyond reading the file, so a {@code ModuleNode} comes back
 * {@linkplain ModuleNode.Resolution#UNCHECKED unchecked}: it has its ports and its edges, and no
 * confirmation that the module behind them is still there. Unchecked is
 * {@linkplain ModuleNode#isMisconfigured() misconfigured}, and a misconfigured node refuses to run.
 * Every path that opens a graph and means to run it therefore ends in a call to {@link #bindAll} —
 * the canvas in {@code GraphCanvas.loadSnapshot}, the supervisor in {@code HeadlessGraph.open}.
 *
 * <h2>Where in the load order it is safe</h2>
 * <b>After the whole graph is placed and every edge is wired</b>, in the same position and for the
 * same reason as resuming an {@code AutoStartable}. {@link ModuleNode#bindTo} rebuilds the node's
 * ports when the module's interface has changed since the graph was saved, and rebuilding removes
 * and re-adds every edge touching the node — which binds by <em>name</em>. Run any earlier and the
 * loader's own edge resolution, which is by index into the node's port lists, would be aiming at
 * ports that are about to move. Run it here and a rebuild re-attaches what it can by name, drops
 * what it cannot, and says so.
 *
 * <h2>An interface that changed is reported, never silent</h2>
 * A module is expected to change independently of the graphs that use it, so a rebuilt shape is the
 * normal case rather than a failure — but it is also the case where a renamed boundary marker
 * silently costs a consumer an edge. {@link Result#reshaped()} names every node whose ports moved,
 * logged as a warning here, so what changed is visible without the user having to notice a missing
 * curve.
 */
public final class ModuleBinding {

    private static final Logger log = Log.get(ModuleBinding.class);

    /**
     * What one {@link #bindAll} pass found.
     *
     * @param bound      the nodes whose module resolved and whose shape already matched it
     * @param reshaped   the nodes whose module resolved with a different interface, so their ports
     *                   were rebuilt and edges may have been dropped
     * @param unresolved the nodes whose module this machine does not have; each keeps its ports,
     *                   its edges and its values, and reports itself misconfigured
     */
    public record Result(List<ModuleNode> bound, List<ModuleNode> reshaped, List<ModuleNode> unresolved) {

        public Result {
            bound = List.copyOf(bound);
            reshaped = List.copyOf(reshaped);
            unresolved = List.copyOf(unresolved);
        }

        /** Whether every module node in the pass found its module. */
        public boolean isComplete() {
            return unresolved.isEmpty();
        }

        /** How many module nodes the pass looked at. */
        public int total() {
            return bound.size() + reshaped.size() + unresolved.size();
        }
    }

    private ModuleBinding() {
    }

    /**
     * Binds every {@link ModuleNode} among {@code nodes}, leaving everything else alone.
     *
     * <p>One node's failure costs only itself: a directory that throws on one id is logged against
     * that node and the pass carries on, exactly as a failed resume does. A node referencing nothing
     * is skipped rather than reported, since an empty reference is the user's unfinished work and
     * the node already says so on its own face.
     *
     * @param nodes     the just-loaded nodes, in load order
     * @param directory where to look each module up; {@link ModuleDirectory#EMPTY} leaves every
     *                  module node unresolved, which is the honest answer for a caller with no
     *                  library
     * @return what resolved, what changed shape, and what could not be found
     */
    public static Result bindAll(Iterable<? extends BaseNode> nodes, ModuleDirectory directory) {
        List<ModuleNode> bound = new ArrayList<>();
        List<ModuleNode> reshaped = new ArrayList<>();
        List<ModuleNode> unresolved = new ArrayList<>();

        for (BaseNode node : nodes) {
            if (!(node instanceof ModuleNode module) || module.getModuleId().isEmpty()) {
                continue;
            }
            List<ModulePort> before = module.getModulePorts();
            boolean resolved;
            try {
                resolved = module.bindTo(directory);
            } catch (RuntimeException failure) {
                // A directory reading files can throw on one module without the rest being suspect,
                // so this is the same containment the resume pass gives one node's start path.
                log.error("Could not resolve the module behind \"" + module.getName()
                        + "\"; it stays unresolved and the rest of the graph is unaffected", failure);
                unresolved.add(module);
                continue;
            }
            if (!resolved) {
                log.warn("{} references module {}, which is not on this machine. Its ports, values and"
                                + " edges are kept exactly as saved; it will not run until the module is there.",
                        module.getName(), module.getModuleId());
                unresolved.add(module);
            } else if (before.equals(module.getModulePorts())) {
                bound.add(module);
            } else {
                log.warn("Module \"{}\" has a different interface than when this graph was saved, so {}"
                                + " rebuilt its ports. Edges re-attach by port name, so an edge on a renamed"
                                + " or removed port has been dropped — check its connections.",
                        module.getModuleName(), module.getName());
                reshaped.add(module);
            }
        }
        return new Result(bound, reshaped, unresolved);
    }
}
