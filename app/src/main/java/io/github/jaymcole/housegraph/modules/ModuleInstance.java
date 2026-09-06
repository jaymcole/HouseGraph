package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeGraph;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.RunScope;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleEntryNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleExitNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleInputNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleOutputNode;
import io.github.jaymcole.housegraph.loader.GraphLoader;
import io.github.jaymcole.housegraph.loader.LoadedGraph;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.saveformat.ClipboardNode;
import io.github.jaymcole.housegraph.saveformat.GraphFileIO;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * One module, standing up and runnable: the referenced graph's nodes live on this
 * {@link NodeGraph}, and its boundary markers are indexed by the names its face is made of.
 *
 * <h2>A nested graph, not a flattened one</h2>
 * The module's nodes are loaded onto a graph of their own rather than merged into the consuming
 * one. See {@code docs/decisions/0012-a-module-runs-as-a-nested-graph.md}.
 *
 * <h2>What an invocation is</h2>
 * {@link #invoke} is one call of the module: seed the values the consumer supplied onto the Module
 * Input markers, run the named Module Entry to completion, then read the Module Output markers and
 * which Module Exits control reached. All of it happens inside a single
 * {@link NodeGraph#runToCompletion driven run}, so the values and the exits an invocation reports
 * are that invocation's own — not a mirror another concurrent invocation may already have
 * overwritten.
 * <p>
 * There is no per-invocation setup and no teardown: the graph is built once and reused. A module
 * holding a resource node or a repeating trigger could not be stood up and torn down per call, and
 * a module that could not hold one would not be worth much.
 *
 * <h2>Depth is carried by the graph</h2>
 * A module inside a module gets its own instance, whose {@link #depth()} is one more than the graph
 * it was built from. That is what {@link #MAX_DEPTH} is measured against — and it is carried on the
 * graph rather than in a thread-local because a driven run executes on the driven graph's own
 * threads, where a thread-local of the driver's would not be.
 */
public final class ModuleInstance extends NodeGraph {

    private static final Logger log = Log.get(ModuleInstance.class);

    /**
     * How deep modules may nest before an invocation refuses.
     *
     * <p>{@code GraphStructureValidator}'s {@code module-cycle} finding already catches a reference
     * loop, statically and without running anything. What it cannot catch is a legitimate nest that
     * simply goes too far: each level costs a graph, an executor and a blocked thread, and deep
     * enough it is a stack overflow rather than a message. Sixteen is far past any nesting a person
     * builds on purpose and far short of anything that hurts.
     */
    public static final int MAX_DEPTH = 16;

    private final int depth;

    /** The graph whose node drives this one, for the setup that does not cross a graph boundary. */
    private final NodeGraph host;

    private final Map<String, ModuleEntryNode> entries = new LinkedHashMap<>();
    private final Map<String, ModuleExitNode> exits = new LinkedHashMap<>();
    private final Map<String, ModuleInputNode> inputs = new LinkedHashMap<>();
    private final Map<String, ModuleOutputNode> outputs = new LinkedHashMap<>();

    /**
     * What one {@link #invoke} produced.
     *
     * @param outputs the value each Module Output marker held, keyed by its declared name; a
     *                declared output nothing wrote holds null, which is written through rather than
     *                omitted so a consumer never reads the previous invocation's value
     * @param exitsReached the declared names of the Module Exits control reached, in file order
     */
    public record Invocation(Map<String, Object> outputs, Set<String> exitsReached) {

        public Invocation {
            outputs = Map.copyOf(new LinkedHashMap<>(outputs));
            exitsReached = Set.copyOf(exitsReached);
        }
    }

    private ModuleInstance(int depth, NodeGraph host) {
        this.depth = depth;
        this.host = host;
    }

    /**
     * Builds the module {@code moduleId} names into a live graph of its own.
     *
     * <h4>What it inherits from the host</h4>
     * The callback executor, so a node inside the module dispatches {@code onExecuted()} where the
     * host's nodes do, and the release timeout, so tearing the module down is bounded the same way.
     * The step delay is not taken here but per invocation, because it is changed while a graph runs.
     *
     * <h4>Nested references are bound to the same directory</h4>
     * A {@code ModuleNode} among the module's own nodes is resolved against {@code directory} as
     * this one was. Without it a nested module would load with its ports intact and no way to find
     * its own file, and would refuse to run.
     *
     * @param moduleId  the module to build
     * @param directory where to read it from, and the registry to build its nodes with
     * @param host      the graph the driving node belongs to, or null when there is none
     * @param depth     how many modules deep this one sits; 1 for a module referenced by a top-level graph
     * @return the built instance, with nothing running yet
     * @throws IllegalStateException if the nesting is too deep, or the directory cannot supply the
     *                               module's graph or a registry to build it with
     */
    public static ModuleInstance open(String moduleId, ModuleDirectory directory, NodeGraph host, int depth) {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(directory, "directory");
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("Modules are nested more than " + MAX_DEPTH + " deep at \""
                    + moduleId + "\"; a module that references itself indirectly reaches this, and so"
                    + " does a nest that is simply too far");
        }
        JSONObject root = directory.rootOf(moduleId);
        if (root == null) {
            throw new IllegalStateException("Module \"" + moduleId + "\" resolved but its graph could"
                    + " not be read back, so there is nothing to run");
        }
        NodeRegistry registry = directory.nodeRegistry();
        if (registry == null) {
            throw new IllegalStateException("Module \"" + moduleId + "\" has no node registry to build"
                    + " its nodes with; a module resolved through a directory that cannot supply one"
                    + " can be described but not run");
        }

        ModuleInstance instance = new ModuleInstance(depth, host);
        if (host != null) {
            instance.setCallbackExecutor(host.getCallbackExecutor());
            instance.setReleaseTimeout(host.getReleaseTimeout());
        }

        // Taken before the load, because a resource node registers its name from onActivated(),
        // which addNode() runs. See warnOnResourceCollisions.
        Map<String, Object> resourcesBefore = resourceSnapshot();
        LoadedGraph loaded = GraphLoader.load(GraphFileIO.fromRoot(root, registry), ClipboardNode::node, instance);
        warnOnResourceCollisions(moduleId, resourcesBefore);

        for (BaseNode node : loaded.nodes()) {
            if (node instanceof ModuleNode nested) {
                nested.bindTo(directory);
            }
        }
        instance.index(loaded);
        return instance;
    }

    /** Indexes the boundary markers by declared name, in file order, so an invocation can find them. */
    private void index(LoadedGraph loaded) {
        for (BaseNode node : loaded.nodes()) {
            if (node instanceof ModuleEntryNode entry) {
                entries.putIfAbsent(entry.getDeclaredName(), entry);
            } else if (node instanceof ModuleExitNode exit) {
                exits.putIfAbsent(exit.getDeclaredName(), exit);
            } else if (node instanceof ModuleInputNode input) {
                inputs.putIfAbsent(input.getDeclaredName(), input);
            } else if (node instanceof ModuleOutputNode output) {
                outputs.putIfAbsent(output.getDeclaredName(), output);
            }
        }
    }

    /**
     * How many modules deep this instance sits: 1 for one referenced by an ordinary graph, and one
     * more for each level below that.
     *
     * @return the nesting depth
     */
    public int depth() {
        return depth;
    }

    /**
     * Whether this module declares any way into its control flow. A module with none is a pure data
     * module: it has values to give and nothing to trigger.
     *
     * @return true if the module has at least one Module Entry
     */
    public boolean hasEntries() {
        return !entries.isEmpty();
    }

    /**
     * Runs the module once.
     *
     * @param entryName the Module Entry to fire, or null to run the module as a pure pull — seed the
     *                  inputs, resolve the outputs, and fire nothing
     * @param values    the value for each Module Input, keyed by declared name; a name the module
     *                  does not declare is ignored
     * @param cancelled the driving node's cancellation signal, which stops this invocation too
     * @return the values the module produced and the exits it reached
     * @throws IllegalStateException     if {@code entryName} names an entry the module does not have
     * @throws java.util.concurrent.CancellationException if {@code cancelled} trips while the module runs
     */
    public Invocation invoke(String entryName, Map<String, Object> values, BooleanSupplier cancelled) {
        ModuleEntryNode entry = null;
        if (entryName != null) {
            entry = entries.get(entryName);
            if (entry == null) {
                throw new IllegalStateException("This module declares no entry named \"" + entryName
                        + "\"; its entries are " + entries.keySet());
            }
        }
        // Read live rather than at build time: the step delay is a debugging aid turned on while a
        // graph is already running, and a run that went opaque the moment it crossed into a module
        // would defeat it. See docs/engine/execution-model.md.
        setStepDelayMillis(host == null ? 0 : host.getStepDelayMillis());

        Map<String, Object> produced = new LinkedHashMap<>();
        Set<String> reached = new LinkedHashSet<>();
        Runnable seed = () -> seedInputs(values);
        java.util.function.Consumer<RunScope> harvest = scope -> harvest(scope, produced, reached);

        if (entry == null) {
            runToCompletion(seed, harvest, cancelled);
        } else {
            runToCompletion(entry, seed, harvest, cancelled);
        }
        return new Invocation(produced, reached);
    }

    /**
     * Writes the consumer's values onto the Module Input markers' <em>output</em> ports — the
     * inversion, and the one place getting it backwards would silently give the interior nothing.
     * Runs inside the invocation's own context, so the values land in that run's overlay and two
     * concurrent invocations cannot see each other's arguments.
     */
    private void seedInputs(Map<String, Object> values) {
        for (Map.Entry<String, ModuleInputNode> declared : inputs.entrySet()) {
            set(declared.getValue().getBoundaryPort(), values.get(declared.getKey()));
        }
    }

    /**
     * Reads one invocation's results while its context is still bound.
     *
     * <p>Every Module Output is <b>pulled</b> rather than read: an Output marker carries no flow
     * port, so control never reaches it, and its value exists only once something resolves it. The
     * pull runs inside the invocation's run, so an interior node the flow already executed is not
     * run a second time — {@code RunScope.pull} short-circuits on its status and reads back what the
     * run computed.
     */
    private void harvest(RunScope scope, Map<String, Object> produced, Set<String> reached) {
        for (Map.Entry<String, ModuleOutputNode> declared : outputs.entrySet()) {
            ModuleOutputNode marker = declared.getValue();
            scope.pull(marker);
            produced.put(declared.getKey(), marker.getBoundaryPort().getValue());
        }
        for (Map.Entry<String, ModuleExitNode> declared : exits.entrySet()) {
            if (scope.hasRun(declared.getValue())) {
                reached.add(declared.getKey());
            }
        }
    }

    /** {@code setValue} erases to {@code setValue(Object)}; the raw variable is how a value of the port's own type is handed over. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void set(NodeVariable variable, Object value) {
        variable.setValue(value);
    }

    /**
     * Warns when standing this module up displaced a {@link ResourceRegistry} name something else
     * was already publishing under.
     *
     * <h4>Detected by what the name points at, not by whether it exists</h4>
     * A displacement leaves the name exactly where it was, so comparing name sets across the load
     * would see nothing. What changes is the object the name resolves to, which is why this
     * snapshots {@code name -> resource} before the load and compares identities after it.
     *
     * <h4>Why this is a warning rather than a refusal or a rename</h4>
     * Registry names are app-wide and are chosen by the user <em>inside</em> the module, so two
     * instances of one module that contains a resource node both register the same name: the second
     * displaces the first, and events published under it are delivered to both instances'
     * listeners. Namespacing the name per instance would fix it and cannot be done from here — every
     * node reaches {@link ResourceRegistry#shared()} directly, including the out-of-tree ones this
     * build cannot change, so a scope they are not passed is a scope they do not honour. Refusing to
     * run any module containing a resource node would ban the single-instance case, which is both
     * useful and correct, along with the broken one.
     * <p>
     * So the collision is <em>named</em>, at the moment it happens, against the module that caused
     * it. A module holding a resource node is usable once per name; a second instance is a mistake,
     * and this is what says so. See {@code docs/nodes/long-lived-resources.md}.
     */
    private static void warnOnResourceCollisions(String moduleId, Map<String, Object> before) {
        for (Map.Entry<String, Object> owned : before.entrySet()) {
            Object now = ResourceRegistry.shared().find(owned.getKey(), Object.class).orElse(null);
            if (now != null && now != owned.getValue()) {
                log.warn("Module \"{}\" registered the resource name \"{}\", which was already in use;"
                        + " the earlier resource has been displaced and both will receive each other's"
                        + " events. Resource names are app-wide, so a module that publishes one can"
                        + " only be used once - see docs/nodes/long-lived-resources.md", moduleId, owned.getKey());
            }
        }
    }

    /** What each registered resource name points at right now, so a displacement can be spotted afterwards. */
    private static Map<String, Object> resourceSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String name : ResourceRegistry.shared().activeNames()) {
            ResourceRegistry.shared().find(name, Object.class).ifPresent(resource -> snapshot.put(name, resource));
        }
        return snapshot;
    }
}
