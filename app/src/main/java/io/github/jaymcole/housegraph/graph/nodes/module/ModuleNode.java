package io.github.jaymcole.housegraph.graph.nodes.module;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node.Disabled;
import io.github.jaymcole.housegraph.annotations.Node.Keywords;
import io.github.jaymcole.housegraph.annotations.Node.Kind;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.modules.ModuleDirectory;
import io.github.jaymcole.housegraph.modules.ModuleEntry;
import io.github.jaymcole.housegraph.modules.ModuleInterface;
import io.github.jaymcole.housegraph.modules.ModulePort;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Another saved graph, referenced as one node. Its ports are the interface that graph's boundary
 * markers declare.
 *
 * <h2>It cannot run</h2>
 * {@link #process(ProcessContext)} always throws. Loading the referenced graph into a child
 * {@code NodeGraph}, seeding its Module Inputs, harvesting its Module Outputs and mapping its
 * Module Exits back onto this node's flow outputs is a separate piece of work that does not exist
 * yet. Failing loudly is deliberate: a no-op would leave every output null and read to the user as
 * a wiring mistake in their own graph. {@code @Disabled} keeps it out of the Add-Node menu for the
 * same reason it keeps the boundary markers out, while leaving the class resolvable so that a graph
 * saved against a future build still opens.
 *
 * <h2>The reference is an id, and the shape is not derived from it</h2>
 * What this node stores is the module's <b>stable id</b> — plus a last-known name and path, which
 * are hints and nothing more (see {@code docs/decisions/0011-modules-are-referenced-by-id.md}).
 * <p>
 * It also stores the <b>whole derived shape</b>: per port, its name, whether it is data or flow,
 * which way it points, and for a data port the declared type's class name. That is not a cache. A
 * save file binds an edge endpoint by port name, and {@code loadState} runs before ports are
 * touched — so rebuilding the ports from the saved shape is what lets a consuming graph load with
 * its edges intact <em>before</em> anything has gone looking for the module file, and at all if the
 * file is gone. See {@code docs/nodes/dynamic-ports.md}.
 *
 * <h2>A missing module never costs the node</h2>
 * There is no placeholder class for an unresolvable module, the way {@code MissingNode} stands in
 * for an unresolvable node <em>type</em>. It would have nothing to do: this class always loads, and
 * everything the file would have told us — the ports, the id, the hints — is already in this node's
 * own state and round-trips through it unchanged. What is missing is only the confirmation that the
 * module still exists, so that is what {@link #getResolution()} records. The node keeps its ports,
 * keeps its edges, reports {@linkplain #isMisconfigured() misconfigured}, refuses to run, and
 * re-saves losing nothing.
 *
 * <h2>What the execution pass still has to decide</h2>
 * Two things this class deliberately does not answer, because answering them without running
 * anything would be guessing. First, a module declaring a Module Exit and no Module Entry gives this
 * node a flow output and no flow input, which is the structural definition of an
 * {@linkplain #isExecutionEntryPoint() execution entry point} — inherited unchanged here, and
 * probably wrong once a module can run. Second, {@link #getExecutionPolicy()} governs concurrent
 * invocations of <em>this</em> node, and says nothing about the child graph it would drive.
 *
 * <h2>Rebuilding is guarded</h2>
 * {@link #bindTo} rebuilds the ports when the module's interface has changed, which removes and
 * re-adds every edge touching this node and so fires the wiring hooks again. A {@code rebuilding}
 * flag makes that churn a no-op instead of a recursion, as {@code ObjectDecomposerNode} does. This
 * node does <em>not</em> react to {@code onInputEdgeAdded}/{@code Removed} at all — its shape comes
 * from the module, never from what happens to be wired to it — so the rebuild only ever starts from
 * a deliberate call.
 */
@Display.Name("Module")
@Display.Description("Runs another saved graph as a single node, with ports from that graph's boundary markers.")
@Kind(NodeKind.ACTION)
@Keywords({"module", "subgraph", "graph", "nested", "reuse", "call", "invoke", "component", "macro"})
@Disabled("Module execution is not implemented; this node can only hold a reference and its shape")
public class ModuleNode extends BaseNode implements NodeContentProvider {

    /** Whether anything has confirmed that the referenced module is actually there. */
    public enum Resolution {
        /** Nothing has looked yet — the state after a plain load, which does no I/O. */
        UNCHECKED,
        /** A directory found the module and this node's ports match its interface. */
        RESOLVED,
        /** A directory was asked and could not find the module. */
        UNRESOLVED
    }

    static final String ID_KEY = "moduleId";
    static final String NAME_KEY = "moduleName";
    static final String PATH_KEY = "modulePath";
    static final String PORTS_KEY = "ports";

    /** The referenced module's stable id, empty when nothing is referenced. Never a path. */
    private volatile String moduleId = "";

    /** Its last-known name, for display only. */
    private volatile String moduleName = "";

    /** Its last-known file path, offered to resolution as a shortcut. Never an identity. */
    private volatile String modulePath = "";

    /** The derived shape this node's ports are built from, in port order. */
    private volatile List<ModulePort> ports = List.of();

    /** Why the module's interface cannot be bound to, from the last successful resolution. */
    private volatile List<String> interfaceProblems = List.of();

    private volatile Resolution resolution = Resolution.UNCHECKED;

    /**
     * This node's row from the save file's root {@code modules} table, retained verbatim.
     *
     * <p>The same reason {@code MissingNode} keeps its {@code plugins} row: the row carries the node
     * libraries the <em>module</em> needs, which were recorded when the module was resolvable and
     * exist nowhere else on a machine that does not have the module file. Re-deriving the row from
     * this node alone would drop them, and a consumer that opened "clean" would fail the moment
     * module execution arrived.
     */
    private JSONObject rawModuleRow;

    /** Guards against reacting to the edge and control churn {@link #rebuildPorts()} causes. */
    private boolean rebuilding;

    private Label statusLabel;

    // --- The reference -------------------------------------------------------------

    /**
     * The referenced module's stable id, empty when this node references nothing.
     *
     * @return the module id, never null
     */
    public final String getModuleId() {
        return moduleId;
    }

    /**
     * The referenced module's last-known name — a label, not an identity.
     *
     * @return the module's name, empty when none is known
     */
    public final String getModuleName() {
        return moduleName;
    }

    /**
     * Where the module was last found. A hint offered to
     * {@code ModuleLibrary.resolve(String, String)}, which trusts it only as far as the id it finds
     * there.
     *
     * @return the last-known path, empty when none was recorded
     */
    public final String getModulePath() {
        return modulePath;
    }

    /**
     * Points this node at a module by id, clearing the resolution so the next
     * {@link #bindTo(ModuleDirectory)} looks again. Does not touch the ports: the shape follows from
     * the module's interface, which only resolution can read.
     *
     * @param id the module's stable id, or null to reference nothing
     */
    public final void setModuleId(String id) {
        String updated = id == null ? "" : id.trim();
        if (updated.equals(moduleId)) {
            return;
        }
        moduleId = updated;
        resolution = Resolution.UNCHECKED;
        refreshStatus();
    }

    // --- The shape -----------------------------------------------------------------

    /**
     * The derived shape this node's ports are built from, in port order and in the consumer's
     * orientation (a module's Module Input is a data <b>in</b>-port here).
     *
     * @return the ports on this module's face, never null
     */
    public final List<ModulePort> getModulePorts() {
        return ports;
    }

    /**
     * Whether anything has confirmed the referenced module exists. {@link Resolution#UNCHECKED}
     * after an ordinary load, because loading a graph does no I/O.
     *
     * @return this node's resolution state
     */
    public final Resolution getResolution() {
        return resolution;
    }

    /**
     * Why the resolved module's interface cannot be bound to — a boundary marker with no name, or
     * two sharing one. Empty after a plain {@code loadState}, which has no module file to check.
     *
     * @return the problems reported by the last resolution, never null
     */
    public final List<String> getInterfaceProblems() {
        return interfaceProblems;
    }

    /**
     * Resolves this node's module against {@code directory} and adopts its interface.
     *
     * <p>The ports are rebuilt only when the shape actually changed, which keeps a re-resolve of an
     * unchanged module free of edge churn. Rebuilding is guarded so the wiring hooks the churn fires
     * cannot re-enter here.
     *
     * @param directory where to look the module up
     * @return true if the module was found; false leaves every port, value and edge untouched and
     *         records {@link Resolution#UNRESOLVED}
     */
    public final boolean bindTo(ModuleDirectory directory) {
        if (rebuilding) {
            return resolution == Resolution.RESOLVED;
        }
        Optional<ModuleEntry> found = directory == null ? Optional.empty() : directory.byId(moduleId);
        if (found.isEmpty()) {
            resolution = Resolution.UNRESOLVED;
            refreshStatus();
            return false;
        }
        ModuleEntry entry = found.get();
        moduleName = entry.name();
        modulePath = entry.path();
        resolution = Resolution.RESOLVED;
        // adopt() ends with the status refresh, so the new name and problems land together.
        adopt(entry.moduleInterface());
        return true;
    }

    /**
     * Adopts an interface directly, for a caller that already has one in hand. Rebuilds the ports
     * only when the shape changed — the unchanged-shape early return is what makes repeated calls
     * idempotent rather than a source of edge churn.
     *
     * @param moduleInterface the face to take on
     */
    public final void adopt(ModuleInterface moduleInterface) {
        if (rebuilding || moduleInterface == null) {
            return;
        }
        interfaceProblems = moduleInterface.problems();
        if (!moduleInterface.ports().equals(ports)) {
            ports = List.copyOf(moduleInterface.ports());
            rebuilding = true;
            try {
                rebuildPorts();
            } finally {
                rebuilding = false;
            }
        }
        refreshStatus();
    }

    // --- Ports ---------------------------------------------------------------------

    @Override
    public void configureInputs() {
        for (ModulePort port : ports) {
            if (port.kind() == ModulePort.Kind.DATA && port.direction() == ModulePort.Direction.IN) {
                addInput(dataPort(port));
            }
        }
    }

    @Override
    public void configureOutputs() {
        for (ModulePort port : ports) {
            if (port.kind() == ModulePort.Kind.DATA && port.direction() == ModulePort.Direction.OUT) {
                addOutput(dataPort(port));
            }
        }
    }

    @Override
    public void configureFlowInputs() {
        for (ModulePort port : ports) {
            if (port.kind() == ModulePort.Kind.FLOW && port.direction() == ModulePort.Direction.IN) {
                addFlowInput(new FlowPort(port.name(), FlowPort.Direction.IN));
            }
        }
    }

    @Override
    public void configureFlowOutputs() {
        for (ModulePort port : ports) {
            if (port.kind() == ModulePort.Kind.FLOW && port.direction() == ModulePort.Direction.OUT) {
                addFlowOutput(new FlowPort(port.name(), FlowPort.Direction.OUT));
            }
        }
    }

    /**
     * Builds one data port from its declared type, degrading to {@code Object} when the declared
     * type is not installed here — the same treatment, and for the same reason, as
     * {@link ModuleDataBoundaryNode}: the declaration is what is saved, and a machine that cannot
     * read it must not rewrite it to something it does happen to understand.
     */
    private static NodeVariable<?> dataPort(ModulePort port) {
        Class<?> declared = ModuleDataBoundaryNode.resolveType(port.typeName());
        Class<?> type = declared == null ? Object.class : declared;
        return typed(port.name(), type);
    }

    private static <T> NodeVariable<T> typed(String name, Class<T> type) {
        return new NodeVariable<>(name, type);
    }

    // --- Behaviour -----------------------------------------------------------------

    /**
     * Always fails. Nothing loads a module into a child graph yet, and a node that quietly did
     * nothing would leave its outputs null and look to the user like a mistake in their own wiring.
     *
     * @param ctx this invocation's context, unused
     * @throws IllegalStateException always
     */
    @Override
    public void process(ProcessContext ctx) {
        throw new IllegalStateException("Module execution is not implemented"
                + (moduleId.isEmpty() ? "" : " (this node references module \"" + label() + "\")"));
    }

    /**
     * True until something has both found the module and read a usable interface off it, on top of
     * the usual unsatisfied-required-input check.
     *
     * <p>An unreferenced or unresolvable module is the obvious case. So is a module whose boundary
     * markers collide, because a port a consumer cannot bind an edge to by name is not a usable
     * interface. And so, for now, is a module nothing has looked for yet: this node cannot run under
     * any circumstances, and reporting otherwise would be a claim the build cannot back.
     *
     * @return true if this node cannot do its job as configured
     */
    @Override
    public boolean isMisconfigured() {
        return moduleId.isEmpty()
                || resolution != Resolution.RESOLVED
                || !interfaceProblems.isEmpty()
                || super.isMisconfigured();
    }

    @Override
    public String getName() {
        return moduleId.isEmpty() ? "Module" : "Module: " + label();
    }

    /** What to call the referenced module in a message: its name if one is known, else its id. */
    private String label() {
        return moduleName.isBlank() ? moduleId : moduleName;
    }

    // --- Persistence ---------------------------------------------------------------

    /**
     * Writes the reference <em>and</em> the whole derived shape, so
     * {@link #loadState(Map)} can rebuild the ports with the module file absent.
     *
     * <p>The id is written here as well as into the save file's per-node {@code module} key. The
     * key is the pointer into the root {@code modules} table, readable in one pure pass before a
     * node exists; this is where the node itself keeps the reference, so that a node's state
     * round-trips on its own — through a copy/paste, or a test that never builds a file.
     */
    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new LinkedHashMap<>();
        if (!moduleId.isEmpty()) {
            state.put(ID_KEY, moduleId);
        }
        if (!moduleName.isBlank()) {
            state.put(NAME_KEY, moduleName);
        }
        if (!modulePath.isBlank()) {
            state.put(PATH_KEY, modulePath);
        }
        if (!ports.isEmpty()) {
            state.put(PORTS_KEY, ModulePort.encode(ports));
        }
        return state;
    }

    /**
     * Restores the reference and the shape. Runs before the ports are first built (see
     * {@code GraphFileIO.fromJson}), so the ports below are constructed from what is restored here
     * without any rebuild — and therefore without touching an edge.
     *
     * @param state what {@link #saveState()} produced
     */
    @Override
    public void loadState(Map<String, String> state) {
        moduleId = value(state, ID_KEY);
        moduleName = value(state, NAME_KEY);
        modulePath = value(state, PATH_KEY);
        ports = List.copyOf(ModulePort.decode(state.get(PORTS_KEY)));
        interfaceProblems = List.of();
        resolution = Resolution.UNCHECKED;
    }

    private static String value(Map<String, String> state, String key) {
        String stored = state.get(key);
        return stored == null ? "" : stored;
    }

    /**
     * This node's row from the save file's root {@code modules} table, or null when there was none.
     *
     * @return the retained row, to be written back out unchanged when nothing better is known
     */
    public JSONObject rawModuleRow() {
        return rawModuleRow;
    }

    /**
     * Hands this node the row the save file recorded for its module, and the id the per-node
     * {@code module} key named.
     *
     * <p>Called by {@code GraphFileIO.fromJson} after {@link #loadState(Map)}. The key is only used
     * when the state carried no id — a file whose node state is intact is the authority on its own
     * reference — so a hand-edited table cannot repoint a node at a different module.
     *
     * @param id  the id from the per-node {@code module} key, or null
     * @param row that module's row from the root {@code modules} table, or null
     */
    public void adoptSavedRow(String id, JSONObject row) {
        if (moduleId.isEmpty() && id != null && !id.isBlank()) {
            moduleId = id.trim();
        }
        if (row != null) {
            // Deep copy through the text form, exactly as MissingNode does: the caller's JSONObject
            // belongs to the parsed file and must not change underneath us, nor us under it.
            rawModuleRow = new JSONObject(row.toString());
            if (moduleName.isBlank()) {
                moduleName = row.optString("name", "");
            }
            if (modulePath.isBlank()) {
                modulePath = row.optString("path", "");
            }
        }
    }

    // --- View ----------------------------------------------------------------------

    @Override
    public javafx.scene.Node createNodeContent() {
        statusLabel = new Label();
        statusLabel.setWrapText(true);
        VBox box = new VBox(4, statusLabel);
        box.setPadding(new Insets(4, 0, 0, 0));
        applyStatus();
        return box;
    }

    /** Every status update goes through {@code present}, so a module inside a running graph has no view to touch. */
    private void refreshStatus() {
        present(this::applyStatus);
    }

    private void applyStatus() {
        if (statusLabel == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        if (moduleId.isEmpty()) {
            lines.add("No module referenced");
        } else {
            lines.add(label());
            if (resolution == Resolution.UNRESOLVED) {
                lines.add("Not found on this machine; the node is kept as-is.");
            }
            lines.addAll(interfaceProblems);
        }
        lines.add("Module execution is not implemented.");
        statusLabel.setText(String.join("\n", lines));
        statusLabel.setStyle("-fx-text-fill: #ff6b6b;");
    }
}
