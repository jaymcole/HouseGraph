package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The model behind "which module should this node reference": what a picker offers, in what order,
 * and described how.
 *
 * <h2>Why modules cannot be Add-Node entries</h2>
 * The Add-Node menu is built from {@code NodeRegistry.discover()}, which is keyed by <b>class</b> —
 * and every module in the world is the same class, {@code ModuleNode}, pointed at a different id. So
 * modules need a listing of their own rather than a place in that menu, and this is it. See
 * {@code docs/decisions/0010-node-search-is-ranked-not-filtered.md} for the same reason stated from
 * the search index's side.
 *
 * <h2>A graph is never offered as a module of itself</h2>
 * Referencing the graph being edited would be a cycle the moment it were saved — the failure
 * {@code GraphStructureValidator}'s {@code module-cycle} exists to report — so the file's own module
 * id is excluded from the offer rather than left to be refused later.
 */
public final class ModuleChoices {

    /**
     * One module as a picker shows it.
     *
     * @param module    the resolved module behind the row
     * @param summary   its interface in one line, e.g. {@code "2 in, 1 out · 1 entry, 2 exits"}
     * @param problems  why it cannot be bound to, empty when it can — a boundary marker with no
     *                  name, or two sharing one
     * @param needs     the node libraries it is built from, which have to be installed where it runs
     */
    public record Choice(ModuleEntry module, String summary, List<String> problems, List<String> needs) {

        public Choice {
            problems = List.copyOf(problems);
            needs = List.copyOf(needs);
        }

        /** The module's declared name, which is what a row is titled. */
        public String name() {
            return module.name();
        }

        /** The module's stable id — the thing a chosen row actually sets on a node. */
        public String id() {
            return module.id();
        }

        /** Whether a node bound to this module would work as-is. */
        public boolean isUsable() {
            return problems.isEmpty();
        }
    }

    private ModuleChoices() {
    }

    /**
     * The modules on offer, by name and then by id so two modules sharing a name still have a stable
     * order.
     *
     * <p>A module with an unbindable interface is offered anyway, with the reason attached: hiding it
     * would leave someone who has just built a module wondering where it went, and a node bound to it
     * reports the same problem on its own face.
     *
     * @param known     every module the library found
     * @param excludeId the module id of the graph being edited, so it is not offered as a module of
     *                  itself; null or blank when the open graph is not a module
     * @return the rows to show, in display order
     */
    public static List<Choice> offer(List<ModuleEntry> known, String excludeId) {
        String excluded = excludeId == null ? "" : excludeId.trim();
        List<Choice> choices = new ArrayList<>();
        for (ModuleEntry entry : known) {
            if (!excluded.isEmpty() && excluded.equals(entry.id())) {
                continue;
            }
            choices.add(new Choice(entry, summarise(entry.moduleInterface().ports()),
                    entry.moduleInterface().problems(), libraryNames(entry)));
        }
        choices.sort(Comparator.comparing(Choice::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Choice::id));
        return List.copyOf(choices);
    }

    /** The interface in one line: data ports first, because most modules have only those. */
    private static String summarise(List<ModulePort> ports) {
        int in = count(ports, ModulePort.Kind.DATA, ModulePort.Direction.IN);
        int out = count(ports, ModulePort.Kind.DATA, ModulePort.Direction.OUT);
        int entries = count(ports, ModulePort.Kind.FLOW, ModulePort.Direction.IN);
        int exits = count(ports, ModulePort.Kind.FLOW, ModulePort.Direction.OUT);
        if (in + out + entries + exits == 0) {
            return "no ports";
        }
        List<String> parts = new ArrayList<>();
        if (in + out > 0) {
            parts.add(in + " in, " + out + " out");
        }
        if (entries + exits > 0) {
            parts.add(plural(entries, "entry", "entries") + ", " + plural(exits, "exit", "exits"));
        }
        return String.join(" · ", parts);
    }

    private static int count(List<ModulePort> ports, ModulePort.Kind kind, ModulePort.Direction direction) {
        return (int) ports.stream().filter(port -> port.kind() == kind && port.direction() == direction).count();
    }

    private static String plural(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }

    private static List<String> libraryNames(ModuleEntry entry) {
        return entry.requiredPlugins().stream().map(GraphDependencyCheck.RequiredPlugin::label).toList();
    }
}
