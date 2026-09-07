package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.catalog.GraphStructureValidator;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleBoundaryNode;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.saveformat.ClipboardNode;
import io.github.jaymcole.housegraph.saveformat.GraphFileIO;
import io.github.jaymcole.housegraph.saveformat.GraphSnapshot;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the app has to decide before and around {@link ModuleLibrary#publish(Path)} — whether
 * this graph may become a module at all, and what to tell the user afterwards.
 *
 * <h2>Publishing is an explicit act</h2>
 * A graph that happens to hold a Module Entry node is not necessarily meant to be a reusable
 * library module; someone may be part-way through building one, or may have copied a marker in while
 * taking a graph apart. Stamping a permanent identity into that file as a side effect of saving it
 * would be a surprising thing for File ▸ Save to do, and an id, once other graphs reference it, is
 * not something the user can take back. So publishing is a command of its own, and
 * {@link ModuleLibrary#publish} is the only thing that mints an id.
 *
 * <h2>What is refused, and what is only warned about</h2>
 * A refusal is for a graph that cannot be a working module for <em>anyone</em>: it cannot be read,
 * it declares no interface, or it takes part in a module reference cycle — which would otherwise be
 * discovered as a stack overflow the first time something loaded it. Everything else is a warning
 * carried back with a successful publish, because it costs the user nothing to publish and fix: a
 * boundary conflict is already reported against the module's own nodes, and a file outside the
 * search roots is publishable and simply will not be found again after a restart.
 *
 * <h2>Both halves are here on purpose</h2>
 * The decisions are the part worth testing, and the window that runs them has no test — so nothing
 * that decides anything belongs in it. The window prompts for a file, calls {@link #publish} on a
 * worker, and renders {@link Result}.
 */
public final class ModulePublisher {

    private static final Logger log = Log.get(ModulePublisher.class);

    /** How a publish ended. */
    public enum Outcome {
        /** The graph had no identity and was given one, which is now on disk. */
        PUBLISHED,
        /** The graph was already a module; its id is unchanged and the index has been refreshed. */
        ALREADY_PUBLISHED,
        /** Nothing was written. {@link Result#reason()} says why. */
        REFUSED
    }

    /**
     * What one {@link #publish} did.
     *
     * @param outcome  what happened
     * @param module   the module it now is, or null when it was refused
     * @param reason   why it was refused, or null when it was not
     * @param warnings things worth saying about a publish that nonetheless succeeded
     */
    public record Result(Outcome outcome, ModuleEntry module, String reason, List<String> warnings) {

        public Result {
            warnings = List.copyOf(warnings);
        }

        /** Whether the file is a published module now, whether or not this call is what made it one. */
        public boolean isPublished() {
            return outcome != Outcome.REFUSED;
        }
    }

    private ModulePublisher() {
    }

    /**
     * Whether this graph declares a module interface — the cheap check a caller makes on what is on
     * the canvas, before prompting for anywhere to write it.
     *
     * <p>Asked of the live nodes rather than of a saved {@code type} id, because the caller has the
     * nodes and not a file yet; {@link ModuleFile#isModule(JSONObject)} is the same question asked of
     * a file.
     *
     * @param snapshot what is on the canvas
     * @return true if at least one boundary marker is in it
     */
    public static boolean declaresInterface(GraphSnapshot snapshot) {
        for (ClipboardNode entry : snapshot.nodes()) {
            if (entry.node() instanceof ModuleBoundaryNode) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gives the graph at {@code file} an identity as a module, or says why it cannot have one.
     *
     * <p>Does file I/O and scans the library's search roots, so it belongs on a worker.
     *
     * @param file    the graph file to publish, already written to disk
     * @param library where the module will be findable, and what mints the id
     * @return what happened, ready to be rendered
     */
    public static Result publish(Path file, ModuleLibrary library) {
        JSONObject root;
        try {
            root = GraphFileIO.readRoot(file.toFile());
        } catch (IOException | RuntimeException e) {
            return refused(file, "It could not be read as a graph: " + e);
        }

        if (!ModuleFile.isModule(root)) {
            return refused(file, "It declares no module boundary markers, so it has no interface for"
                    + " another graph to connect to. Add a Module Input, Module Output, Module Entry or"
                    + " Module Exit node and publish again.");
        }

        String cycle = cycleIn(root, library);
        if (cycle != null) {
            return refused(file, "It takes part in a module reference cycle, which nothing could load: "
                    + cycle);
        }

        boolean hadId = ModuleFile.idOf(root) != null;
        ModuleEntry published;
        try {
            published = library.publish(file);
        } catch (IOException | RuntimeException e) {
            return refused(file, "Its module id could not be written back: " + e);
        }

        List<String> warnings = new ArrayList<>();
        if (!withinSearchRoots(file, library)) {
            warnings.add("This file is outside " + describe(library.searchRoots())
                    + ", so it will not be found again after a restart. Keep a module in the modules"
                    + " folder, or beside the graphs that reference it.");
        }
        warnings.addAll(published.moduleInterface().problems());

        log.info("Published {} as module {} ({})", file, published.name(), published.id());
        return new Result(hadId ? Outcome.ALREADY_PUBLISHED : Outcome.PUBLISHED, published, null, warnings);
    }

    /**
     * The reference cycle this file takes part in, described the way the validator describes it, or
     * null when there is none.
     *
     * <p>Run against the file as it is on disk, before an id has been minted: a graph with no id yet
     * cannot be referenced by anything, so the only cycle a first publish could have is none, and
     * the check earns its keep on the second — a module that has since been wired back into one of
     * its own consumers.
     */
    private static String cycleIn(JSONObject root, ModuleLibrary library) {
        GraphStructureValidator.Report report =
                GraphStructureValidator.inspect(root, library.nodeRegistry(), library::rootOf);
        return report.findings().stream()
                .filter(finding -> GraphStructureValidator.Codes.MODULE_CYCLE.equals(finding.code()))
                .map(GraphStructureValidator.Finding::message)
                .findFirst()
                .orElse(null);
    }

    /** Whether {@code file} sits directly in one of the directories the library scans. */
    private static boolean withinSearchRoots(Path file, ModuleLibrary library) {
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return false;
        }
        return library.searchRoots().stream()
                .map(root -> root.toAbsolutePath().normalize())
                .anyMatch(parent::equals);
    }

    private static String describe(List<Path> roots) {
        return roots.isEmpty() ? "any module search directory"
                : String.join(" or ", roots.stream().map(Path::toString).toList());
    }

    private static Result refused(Path file, String reason) {
        log.warn("Not publishing {} as a module: {}", file, reason);
        return new Result(Outcome.REFUSED, null, reason, List.of());
    }
}
