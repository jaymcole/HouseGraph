package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleBoundaryNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleDataBoundaryNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleEntryNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleExitNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleInputNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleOutputNode;
import io.github.jaymcole.housegraph.saveformat.ClipboardNode;
import io.github.jaymcole.housegraph.saveformat.GraphFileIO;
import io.github.jaymcole.housegraph.saveformat.GraphSnapshot;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The face a graph presents when it is used as a module: every port derived from its boundary
 * markers, plus whatever makes that face unusable.
 *
 * <h2>Derived, in file order</h2>
 * {@link #derive} builds the graph's nodes from a parsed save file and reads every
 * {@link ModuleBoundaryNode} it finds, in the order the file lists them. File order is the only
 * ordering available and it is stable, which is what the save format needs: an edge endpoint whose
 * name is blank or ambiguous falls back to a positional reference, so the port order a module
 * produces must not depend on iteration luck.
 * <p>
 * Reading the markers goes through their accessors ({@code getDeclaredName()},
 * {@code getDeclaredTypeName()}), never through {@code saveState()}: the state map is a
 * persistence detail of those classes, and a declared type this machine cannot load is exactly the
 * case where the two differ — {@code getDeclaredType()} degrades to {@code Object} while the
 * declaration is kept intact. What is recorded here is the <b>declaration</b>, so a module's face
 * is the same on every machine.
 *
 * <h2>Problems are part of the answer</h2>
 * A boundary marker with no name, or two markers sharing a name on the same side, produce an
 * interface a consumer cannot bind to: {@code docs/engine/save-format.md} binds an edge endpoint by
 * name only where that name is non-blank and unique on the node, so such a port silently falls back
 * to a positional reference that any later edit invalidates. That is a fact about a <em>set</em> of
 * markers, which no single marker can see — {@code ModuleBoundaryNode} says as much — so it is
 * detected here, the one place the whole set is in hand, and reported in {@link #problems()} rather
 * than thrown. The interface is still returned in full: a consumer that already has ports for those
 * names must keep them, and be told it is broken, rather than lose them.
 * <p>
 * {@code GraphStructureValidator} reports the same conflicts as findings with a JSON Pointer, for a
 * caller checking a module file from the command line.
 *
 * @param ports    every port on the module's face, in the file's node order
 * @param problems why this face cannot be bound to, empty when it is sound
 */
public record ModuleInterface(List<ModulePort> ports, List<String> problems) {

    /** An interface with no ports and nothing wrong with it — what an empty graph declares. */
    public static final ModuleInterface EMPTY = new ModuleInterface(List.of(), List.of());

    public ModuleInterface {
        ports = List.copyOf(ports);
        problems = List.copyOf(problems);
    }

    /** Whether this face can be bound to — no blank names, no collisions. */
    public boolean isSound() {
        return problems.isEmpty();
    }

    /**
     * Derives the interface a parsed module file declares.
     *
     * <p>Pure given an already-parsed root: it instantiates node classes and applies their saved
     * state (the same thing {@code GraphFileIO.fromJson} does, and for the same reason — a
     * declaration lives in a marker's state, so the state has to be applied before it can be read),
     * but touches no file and makes no network call. Finding the file is
     * {@link ModuleLibrary}'s job.
     *
     * @param root     a parsed save file (see {@code GraphFileIO.readRoot})
     * @param registry resolves each node's saved type to a class, exactly as loading would
     * @return the derived face, with any conflicts among the markers reported in {@link #problems()}
     */
    public static ModuleInterface derive(JSONObject root, NodeRegistry registry) {
        GraphSnapshot snapshot = GraphFileIO.fromRoot(root, registry);
        List<ModulePort> ports = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        // Keyed by the list a port would land in on the consuming node, since that is the scope the
        // save format resolves a name against: data inputs, data outputs, flow inputs, flow outputs.
        Map<String, Set<String>> seenPerSide = new LinkedHashMap<>();

        for (ClipboardNode entry : snapshot.nodes()) {
            BaseNode node = entry.node();
            if (!(node instanceof ModuleBoundaryNode marker)) {
                continue;
            }
            ModulePort port = portFor(marker);
            if (port == null) {
                continue;
            }
            ports.add(port);
            if (port.name().isBlank()) {
                problems.add(describe(marker) + " declares no name, so nothing can bind to it");
                continue;
            }
            String side = port.kind() + "/" + port.direction();
            if (!seenPerSide.computeIfAbsent(side, key -> new LinkedHashSet<>()).add(port.name())) {
                problems.add("two " + describe(marker) + " markers both declare \"" + port.name()
                        + "\"; a port name has to be unique on the side it lands on");
            }
        }
        return new ModuleInterface(ports, problems);
    }

    /**
     * The consumer-facing port one marker declares, or null for a marker type this build does not
     * know. <b>Every arm inverts</b> — see {@link ModulePort} — so a Module Input, whose own port is
     * a data output, becomes a data port the consumer feeds.
     */
    private static ModulePort portFor(ModuleBoundaryNode marker) {
        if (marker instanceof ModuleInputNode input) {
            return new ModulePort(input.getDeclaredName(), ModulePort.Kind.DATA, ModulePort.Direction.IN,
                    declaredTypeName(input));
        }
        if (marker instanceof ModuleOutputNode output) {
            return new ModulePort(output.getDeclaredName(), ModulePort.Kind.DATA, ModulePort.Direction.OUT,
                    declaredTypeName(output));
        }
        if (marker instanceof ModuleEntryNode entry) {
            return new ModulePort(entry.getDeclaredName(), ModulePort.Kind.FLOW, ModulePort.Direction.IN, "");
        }
        if (marker instanceof ModuleExitNode exit) {
            return new ModulePort(exit.getDeclaredName(), ModulePort.Kind.FLOW, ModulePort.Direction.OUT, "");
        }
        return null;
    }

    /** The declaration, not the resolution: a type this machine lacks is carried across unchanged. */
    private static String declaredTypeName(ModuleDataBoundaryNode marker) {
        return marker.getDeclaredTypeName();
    }

    /** "Module Entry" from {@code ModuleEntryNode} — a marker named the way its menu entry is. */
    private static String describe(ModuleBoundaryNode marker) {
        return marker.getClass().getSimpleName()
                .replaceAll("Node$", "")
                .replaceAll("(?<=[a-z])(?=[A-Z])", " ");
    }
}
