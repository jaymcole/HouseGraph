package io.github.jaymcole.housegraph.graph.nodes.module;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node.Keywords;
import io.github.jaymcole.housegraph.annotations.Node.Kind;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.modules.ModuleDirectory;
import io.github.jaymcole.housegraph.modules.ModuleEntry;
import io.github.jaymcole.housegraph.modules.ModuleInstance;
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
import java.util.Set;

/**
 * Another saved graph, referenced as one node. Its ports are the interface that graph's boundary
 * markers declare.
 *
 * <h2>How it runs</h2>
 * The referenced module is loaded onto a {@link ModuleInstance} — a {@code NodeGraph} of its own —
 * <b>once</b>, lazily, on the first invocation. {@link #process(ProcessContext)} then drives one run
 * of that graph and blocks until it settles: the values on this node's data in-ports are seeded onto
 * the module's Module Input markers, the Module Entry named by the flow-in port control arrived at
 * is run to completion, and then the Module Outputs are read back and the Module Exits control
 * reached select this node's flow-out ports. See {@code docs/engine/execution-model.md} and
 * {@code docs/decisions/0012-a-module-runs-as-a-nested-graph.md}.
 * <p>
 * <b>Built once, not per call.</b> A module holding a resource node or a repeating trigger cannot be
 * stood up and torn down per invocation. Lazily rather than from {@link #onActivated()}, because
 * that fires during load, before this node's edges are wired, and would make opening a graph do
 * module I/O. The instance is disposed from {@link #releaseResources()}.
 * <p>
 * <b>Nothing is shared between invocations.</b> Each is one driven run with its own
 * {@code ExecutionContext}, and its inputs, its outputs and the exits it reports are read inside it.
 * That is what makes {@link io.github.jaymcole.housegraph.graph.ExecutionPolicy#PARALLEL} on this
 * node mean concurrent invocations of one module rather than two invocations corrupting each other;
 * interior nodes keep their own policies, so a stateful one still serializes on the default
 * {@code QUEUE}. See {@code docs/engine/execution-policy.md}.
 * <p>
 * <b>A pull is data-only.</b> With no flow-in port arrived at — a data dependency, a {@code resolve},
 * or a run triggered on this node directly — the module's Module Outputs are resolved and no Entry is
 * fired, which is what asking a node for a value means everywhere else in the engine. A module with
 * no Entry at all is only ever used this way.
 * <p>
 * <b>An interior node that fails does not fail this one</b>, exactly as a failed node does not fail
 * the run it is in. The module's run carries on, its outputs are whatever did resolve, and the
 * failure is recorded against the interior node and logged there.
 * <p>
 * <b>A module's own triggers do not reach its consumer.</b> A repeating trigger inside a module
 * fires runs on the module's graph, and those runs are not the invocation this node drove — so an
 * Exit they reach selects nothing here. What crosses back out is what one invocation reached. A
 * module that must drive its consumer's flow needs a trigger in the consuming graph.
 * <p>
 * <b>The Add-Node menu cannot point one of these at a module.</b> That menu is built from
 * {@code NodeRegistry.discover()}, which is keyed by class — and every module in the world is this
 * one class pointed at a different id, so the menu can only ever offer a node referencing nothing.
 * The canvas context menu's <b>Add Module…</b> row is the one that offers modules: it lists what
 * {@code ModuleLibrary} found and hands back a node with {@link #setModuleId} and {@link #bindTo}
 * already called on it. See {@code io.github.jaymcole.housegraph.modules.ModuleChoices}. A node
 * added from the Add-Node menu instead says on its own face that it references nothing, and where to
 * go to fix that.
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
public class ModuleNode extends BaseNode implements NodeContentProvider {

    private static final Logger log = Log.get(ModuleNode.class);

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

    /**
     * Where the module was resolved from, kept because the same directory is what stands it up:
     * {@link ModuleDirectory#rootOf} and {@link ModuleDirectory#nodeRegistry()} are the running half
     * of the lookup {@link #bindTo} already does. Null until something binds this node.
     */
    private volatile ModuleDirectory directory;

    /**
     * The module, standing up. Built on first use and reused; null before that and after teardown.
     * Guarded by {@link #instanceLock} for the build, {@code volatile} for the read.
     */
    private volatile ModuleInstance instance;

    /** Set by {@link #onRemoved()}, so a firing racing the removal does not build a graph nobody will dispose. */
    private volatile boolean detached;

    private final Object instanceLock = new Object();

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
        discardInstance(false);
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
        // Kept whatever the outcome: a re-bind against a different directory must not leave the old
        // one behind to be run from, and a failed resolution has nothing to run anyway.
        this.directory = directory;
        discardInstance(false);
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
     * Runs the module once and brings its answers back across the boundary.
     *
     * <h4>The four steps</h4>
     * <ol>
     *   <li><b>Choose an entry.</b> The flow-in port control arrived at names the Module Entry to
     *       fire (see {@link #entryFor}). Nothing arrived means a pull, and no entry is fired.</li>
     *   <li><b>Snapshot the inputs.</b> Read <em>here</em>, on this run's thread with this run's
     *       context bound, and carried into the invocation as plain values. Reading them from inside
     *       the module's run would read this node's committed mirror instead — last-run-wins across
     *       concurrent runs, and so the wrong value exactly when it matters.</li>
     *   <li><b>Run it, blocking.</b> {@link ModuleInstance#invoke} does not return until the module's
     *       run has quiesced, which it must: the engine reads this node's activated flow-out ports
     *       the moment {@code process()} returns, so an {@code activate} decided later would be too
     *       late to select anything.</li>
     *   <li><b>Apply the answers.</b> Every declared output is written, including the ones the module
     *       left null, so nothing reads the previous invocation's value; every exit that was reached
     *       activates its port, and reaching none activates nothing at all.</li>
     * </ol>
     *
     * @param ctx this invocation's context: which flow-in port fired, and the cancellation that
     *            stops the module's run along with this one
     * @throws IllegalStateException if this node cannot run as configured (see {@link #isMisconfigured()}),
     *                               or the module cannot be stood up
     */
    @Override
    public void process(ProcessContext ctx) {
        if (isMisconfigured()) {
            throw new IllegalStateException(misconfigurationReason());
        }
        ModuleInstance module = instance();
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (NodeVariable<?> input : getInputs()) {
            arguments.put(input.name, input.getValue());
        }

        String entry = entryFor(ctx);
        ModuleInstance.Invocation invocation = module.invoke(entry, arguments, ctx::isCancelled);
        // A module is an opaque box in its consumer's graph: nothing else on the canvas says which
        // way control went into it or came back out. At debug, so it is in the log file an operator
        // reads after the fact and nowhere else.
        log.debug("Module \"{}\" invoked {}, reached exits {}", label(),
                entry == null ? "as a pull" : "at entry \"" + entry + "\"", invocation.exitsReached());

        for (NodeVariable<?> output : getOutputs()) {
            set(output, invocation.outputs().get(output.name));
        }
        applyExits(invocation.exitsReached());
    }

    /**
     * The Module Entry this firing should fire, or null to run the module as a pull.
     *
     * <h4>Why an empty arrival means a pull</h4>
     * {@link ProcessContext#triggeredVia()} is empty whenever no flow edge was involved: a
     * {@code resolve}, a data dependency, or a run triggered on this node directly. Asking a node for
     * a value never cascades flow anywhere else in the engine, and a module is not an exception — so
     * a pull resolves the module's Module Outputs and fires no Entry, whether or not the module
     * declares one. A module with Entries that is pulled therefore answers with whatever its outputs
     * resolve to without its control flow having run, exactly as pulling any other stateful node
     * does. To <em>run</em> a module, wire a trigger into one of its flow-in ports.
     *
     * <h4>And why only one</h4>
     * Several ports appear together only when they genuinely arrived together — a flow join, or
     * concurrent sibling branches. Two entries are two behaviours, and
     * {@code docs/engine/execution-model.md} is explicit that two ports on one node are not two
     * behaviours within a single run: distinct behaviours belong to distinct triggers. So the first
     * in port order wins and the rest are named in a warning rather than silently run.
     */
    private String entryFor(ProcessContext ctx) {
        Set<FlowPort> arrived = ctx.triggeredVia();
        if (arrived.isEmpty()) {
            return null;
        }
        List<FlowPort> fired = getFlowInputs().stream().filter(arrived::contains).toList();
        if (fired.isEmpty()) {
            return null;
        }
        if (fired.size() > 1) {
            log.warn("Control reached {} at several entries at once ({}); running \"{}\" only, because"
                            + " two entries are two behaviours and so two triggers", getName(),
                    fired.stream().map(port -> port.name).toList(), fired.get(0).name);
        }
        return fired.get(0).name;
    }

    /**
     * Fires the flow-out ports whose exits were reached, and none at all when none was.
     *
     * <p>The explicit {@link #activateNone()} is load-bearing: never calling {@code activate} means
     * "fire every out-port", so a module that finished without reaching an exit — or that was pulled,
     * where no exit can be reached — would otherwise fire all of them and cascade a branch the module
     * never chose.
     */
    private void applyExits(Set<String> reached) {
        boolean any = false;
        for (FlowPort port : getFlowOutputs()) {
            if (reached.contains(port.name)) {
                activate(port);
                any = true;
            }
        }
        if (!any) {
            activateNone();
        }
    }

    /** {@code setValue} erases to {@code setValue(Object)}; the raw variable is how a harvested value is handed back. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void set(NodeVariable variable, Object value) {
        variable.setValue(value);
    }

    /**
     * The module, standing up — built on first use and kept.
     *
     * <p>Under the double-checked build the lock is only ever held for the load, never for an
     * invocation, so a second run that arrives while the first is inside the module waits for the
     * build and not for the run. A node already removed refuses rather than building a graph whose
     * disposal has already been and gone.
     */
    private ModuleInstance instance() {
        ModuleInstance existing = instance;
        if (existing != null) {
            return existing;
        }
        synchronized (instanceLock) {
            if (instance == null) {
                if (detached) {
                    throw new IllegalStateException(getName() + " has been removed from its graph");
                }
                instance = ModuleInstance.open(moduleId, directory, getOwningGraph(), depth());
            }
            return instance;
        }
    }

    /**
     * How deep this node's module will sit. A module referenced from an ordinary graph is depth 1;
     * one referenced from inside another module is one deeper than the graph it is on, which is what
     * a {@link ModuleInstance} being a {@code NodeGraph} in its own right makes readable. A
     * thread-local could not: a driven run executes on the driven graph's threads, not the driver's.
     */
    private int depth() {
        return (getOwningGraph() instanceof ModuleInstance enclosing ? enclosing.depth() : 0) + 1;
    }

    /**
     * Lets go of a built module, so the next invocation builds against whatever is referenced now.
     *
     * @param inline whether to dispose on the calling thread. True only from
     *               {@link #releaseResources()}, which is already on a worker under the graph's own
     *               time bound — that is where the disposal is <em>meant</em> to be waited for.
     *               False from a re-point, which can come from the FX thread, where waiting out the
     *               module's own teardown would freeze the UI. The same split, and the same reason,
     *               as {@code NodeGraph.removeNode}'s.
     */
    private void discardInstance(boolean inline) {
        ModuleInstance stale;
        synchronized (instanceLock) {
            stale = instance;
            instance = null;
        }
        if (stale == null) {
            return;
        }
        if (inline) {
            stale.dispose();
        } else {
            Thread.ofVirtual().name("dispose-module-" + moduleId).start(stale::dispose);
        }
    }

    /**
     * Always false. A module node never initiates its own execution: it runs when control arrives at
     * a flow-in port, or when something pulls it.
     *
     * <p>The structural default would say otherwise for a module that declares a Module Exit and no
     * Module Entry — a flow output with no flow input, which is how the engine recognises a trigger.
     * That module's exit can only be reached by control the module raises itself, and a module's own
     * triggers fire runs on its own graph, which are not the invocation this node drove. So the
     * default would mark as a trigger a node that cannot trigger anything.
     *
     * @return false, always
     */
    @Override
    public boolean isExecutionEntryPoint() {
        return false;
    }

    /**
     * Stops this node accepting invocations. The graph itself is disposed in the slow half — see
     * {@link #releaseResources()} — because {@code dispose()} waits on every node in the module and
     * this hook runs on the removing thread, which in the app is the FX thread.
     */
    @Override
    protected void onRemoved() {
        detached = true;
    }

    /**
     * Disposes the module's graph: its nodes are removed and released, and its executor and watchdog
     * scheduler are shut down. Skipping it would leak both, plus whatever the module's own nodes hold
     * — a running timer, a socket — with nothing left pointing at them.
     *
     * <p>Idempotent, as a teardown hook must be: the second call finds nothing to dispose.
     */
    @Override
    protected void releaseResources() {
        discardInstance(true);
    }

    /**
     * True until something has both found the module and read a usable interface off it, on top of
     * the usual unsatisfied-required-input check.
     *
     * <p>An unreferenced or unresolvable module is the obvious case. So is a module whose boundary
     * markers collide, because a port a consumer cannot bind an edge to by name is not a usable
     * interface. And so is a module nothing has looked for yet — not merely because the file is
     * unconfirmed, but because {@link #bindTo} is also what hands this node the directory it would
     * stand the module up from: an unbound node has nothing to run even if the file is there.
     *
     * <p>{@link #process(ProcessContext)} refuses on exactly this, rather than testing its own
     * differently-worded conditions, so a node the UI flags as broken and a node that will not run
     * are the same node.
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

    /** Why {@link #isMisconfigured()} is true, in the terms whoever wired this node would recognise. */
    private String misconfigurationReason() {
        if (moduleId.isEmpty()) {
            return getName() + " references no module";
        }
        if (resolution == Resolution.UNRESOLVED) {
            return "Module \"" + label() + "\" was not found on this machine";
        }
        if (resolution == Resolution.UNCHECKED) {
            return "Module \"" + label() + "\" has not been resolved, so there is nothing to run it from";
        }
        if (!interfaceProblems.isEmpty()) {
            return "Module \"" + label() + "\" has an interface nothing can bind to: "
                    + String.join("; ", interfaceProblems);
        }
        return getName() + " has a required input with no value source";
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
            // The Add-Node menu can only produce this state, so the label is also the way out of it.
            lines.add("No module referenced");
            lines.add("Right-click the canvas and choose Add Module… to pick one.");
        } else {
            lines.add(label());
            if (resolution == Resolution.UNRESOLVED) {
                lines.add("Not found on this machine; the node is kept as-is.");
            } else if (resolution == Resolution.UNCHECKED) {
                lines.add("Not resolved yet.");
            }
            lines.addAll(interfaceProblems);
        }
        statusLabel.setText(String.join("\n", lines));
        statusLabel.setStyle(isMisconfigured() ? "-fx-text-fill: #ff6b6b;" : "");
    }
}
