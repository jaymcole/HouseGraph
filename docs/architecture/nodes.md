# Nodes: the model, discovery, and how to add one

Nodes are the units a user wires together. Every node extends `BaseNode` and
lives under `graph/nodes/<category>/`. There is **no registration step** — nodes
are discovered on the classpath.

## The node model

A `BaseNode` declares four kinds of port, each via a `configure*` hook:

- **Inputs** (`configureInputs` → `addInput`) — `NodeVariable<T>` data slots read
  during `process()`.
- **Outputs** (`configureOutputs` → `addOutput`) — `NodeVariable<T>` data slots
  written during `process()`.
- **Flow inputs** (`configureFlowInputs` → `addFlowInput`) — `FlowPort`(s) where
  control can enter. Default: none (can't be triggered along a flow edge, but can
  still be pulled as a data dependency). Most executable nodes add one unnamed
  port.
- **Flow outputs** (`configureFlowOutputs` → `addFlowOutput`) — `FlowPort`(s)
  where control leaves. A plain node adds one unnamed port; a branch node adds
  several named ports and picks with `activate()`. A **loop** node fires one of
  its ports *repeatedly* — once per item — with `runFlowBranchToCompletion()`
  instead (see below); `graph/nodes/control/ForEachNode.java` is the canonical one.

`configure*` hooks run lazily on first port access (not from the constructor, so
subclass field initializers have already run). `reconfigure()` / `rebuildPorts()`
rebuild the lists for nodes whose ports depend on editable settings.

### `NodeVariable<T>`

A typed slot with a `name`, a stable `id`, a `type`, and a `manuallyEditable`
flag. Persistence rules (enforced by `GraphFileIO`):

- `isPersistentValue()` = `manuallyEditable && !secret && !transient`. **Only
  manually-authored, non-secret, non-transient values are saved.** Computed
  outputs are left null and recomputed on load.
- `markSecret()` — value holds a secret; never written to disk. Persist a
  *reference* to the secret (its `SecretsStore` key), not the value.
- `transientValue()` — value is a live runtime handle (e.g. a Discord reply);
  meaningful only within one pass, never persisted.

- `required()` — this input must have a value source (an incoming data edge **or** a
  non-null manually-authored value) or its node is **misconfigured**. Optional by default,
  so no existing node changes until its author opts in. Authors set the *default* via the fluent
  `required()`; the user can override it per-input from the node's right-click "Required inputs"
  menu, and that choice **is** persisted (a `requiredInputs` boolean array — see
  [ui.md](ui.md) / `GraphFileIO`). `BaseNode.getUnsatisfiedRequiredInputs()` / `isMisconfigured()`
  evaluate this headlessly against the node's current wiring and values; the UI renders the result
  as a red node border, red input-port anchors, and a tooltip.

`markSecret()`, `transientValue()`, and `required()` are all fluent, for field initialization.
Many library nodes already ship with author-required inputs where a missing value makes the node
meaningless — e.g. the string converters' `in`, the viewers' displayed value, the `If` nodes'
condition, the object decomposer's `Object`, and the Discord Send/Reply message + target. Nodes
with a sensible default for a missing input (e.g. `Add`, whose operands default to 0) leave them
optional.

**Anchor type & hidden converters.** A `NodeVariable`'s `type` (a `Class<?>`) governs which
outputs may feed it. An edge is allowed when the input type is assignable from the output type
**or** a converter is registered for the pair in `TypeConverters` — so an `Integer`, `Double`, or
`Boolean` output can feed a `Float` input, coerced transparently at value handoff. Register your
own with `TypeConverters.register(from, to, fn)` (the plugin extension point). This is separate
from the explicit converter **nodes** under `graph/nodes/converters/`, which stay for visible,
first-class conversions and for targets the built-in numeric/boolean matrix does not cover (e.g.
`*` → `String`). See [graph-engine.md](graph-engine.md#data-edge-type-compatibility--hidden-converters).

### Execution policy

Every node carries an `ExecutionPolicy` (`getExecutionPolicy()`/`setExecutionPolicy()`,
default `QUEUE`, persisted by `GraphFileIO`) that governs what happens when the node is
**re-entered while work it started is still in flight** — drop it, restart, or queue (coalesced).
It applies at two scopes (a whole run at an entry node, one node's `process()` mid-cascade); the
engine, not the node, enforces it — see [graph-engine.md](graph-engine.md) and the detailed section
below.

### Flow joins (AND-barriers)

Because a run is fire-and-forget (see [graph-engine.md](graph-engine.md)), an ordinary node
reached by several flow branches fires on the **first** arrival (OR/merge). A node that overrides
`BaseNode.isFlowJoin()` to return `true` instead waits for **all** its wired incoming flow edges
before firing (AND) — the way to reconverge parallel branches. `graph/nodes/control/JoinNode.java`
is the concrete node (numbered flow-in ports, adjustable 2–8). The engine counts arrivals per run
(`ExecutionContext`) and fires the join once they reach its wired-edge count; an unwired port
doesn't count, and a join whose branch is pruned by an upstream `If` just doesn't fire that run.

### Loops (for-each)

A node that must fire a flow output **once per item** can't do it with `activate()` — the cascade
fires each downstream node at most once per run. Instead it calls the protected
`BaseNode.runFlowBranchToCompletion(port, seed)` from `process()`, once per item. Each call runs
that port's branch as a **fresh isolated sub-run** (so the per-run flow dedup resets and the body
runs afresh), seeded with the loop's per-item outputs — the loop node is pre-marked complete in the
sub-run so the body pulls the seeded values without re-running the loop. The call **blocks** until
the body subtree finishes, so iterations run **sequentially**. `graph/nodes/control/ForEachNode.java`
is the concrete node: it drives a **Body** port per element (exposing `Current Item` / `Index`),
then `activate`s a **Completed** port once at the end. See
[graph-engine.md](graph-engine.md#loop-bodies-seeded-sub-runs) for the mechanism.

### Execution policy (two scopes)

The policy is enforced at two scopes, and matters for **any node a run flows through** (it's inert
only on a pure data node with no flow ports):

- **At an execution entry point** — a node `execute()` is called on directly (a trigger button, a
  timer, an inbound event) — it gates the **whole run** started by a re-trigger.
  `BaseNode.isExecutionEntryPoint()` marks these (default: "has a flow-out but no flow-in"; a node
  that self-triggers *and* has a flow-in, like `DiscoverCamerasNode`'s Discover button, overrides it
  to `true`).
- **At a mid-cascade node** — one reached along a flow edge — it gates re-entry of that node's own
  `process()` when a *second* run overlaps it. This lets two branches of one trigger differ (a slow
  branch that `DROP`s overlaps, a fast branch that `QUEUE`s them). A consequence of the `QUEUE`
  default: two `PARALLEL` runs fanning into a shared node serialize at it unless it's set `PARALLEL`.

The UI shows the policy glyph and right-click policy submenu for **any node that participates in
flow** (`participatesInFlow()`), at both scopes. See [graph-engine.md](graph-engine.md) and
[`../design/per-node-execution-policy.md`](../design/per-node-execution-policy.md) for the mechanics.

### Per-node throughput: concurrency limit & timeout

Two knobs on `BaseNode`, orthogonal to execution policy, for any node that does real work
(an LLM call, a camera poll, a transform): `setMaxConcurrency(n)` caps how many runs may
execute this node's `process()` at once across all concurrent runs (0 = unlimited; a per-node
fair semaphore in the engine), and `setTimeoutMillis(ms)` aborts a `process()` that overruns
(0 = none; the engine interrupts it and marks the node FAILED with a `TimeoutException`). Both
are cooperative on interruption and persist via `GraphFileIO`. Set them from the node's
right-click menu (shown for any flow-participating node, not just triggers).

## Discovery: `NodeRegistry`

`NodeRegistry.discover()` scans the classpath under
`io.github.jaymcole.housegraph.graph.nodes` (both exploded dirs and jars) for
concrete `BaseNode` subclasses and returns an `Entry(nodeClass, categoryPath,
displayName)` for each. The UI builds the Add-Node menu from this, grouped by the
**subpackage/folder** the class sits in.

- **Dropping a class under `graph/nodes/<anything>/` is all it takes** to make it
  appear. The folder name becomes its menu category.
- `@Node.Disabled` hides a class from the menu but keeps it *loadable*, so a graph
  saved while a node type was enabled still opens after it's disabled.
- `resolveClass(name)` maps a saved fully-qualified class name back to a loadable
  node type; `instantiate(class)` builds one via its no-arg constructor;
  `duplicate(source)` clones a node for copy/paste by copying input/output values
  positionally (no per-type `clone()` needed, since `configure*` always builds the
  same list shape for a given class). It carries across **only persistent values**
  (`NodeVariable.isPersistentValue` — manually-authored, non-secret, non-transient),
  exactly as save-to-file does; computed outputs and values a node resolved off an
  edge (a secret in particular) are left out rather than pasted in as manual entries.

## Node categories (current folders under `graph/nodes/`)

`camera`, `constants`, `control`, `converters`, `debug`, `discord`, `iot`,
`loader`, `math`, `ml`, `object`, `resource`, `viewers`, `web`.

`web` holds nodes for hosting on the local network — currently the web-server
resource node, which serves a directory of static files as `<name>.local` and
drives the `web` package's `LocalWebServer` (mirroring how `camera`/`discord`
nodes drive their client packages). See [integrations.md](integrations.md).

`ml` holds nodes backed by locally-run machine-learning models. They drive the
JVM-native inference clients in the `ml` package (models run through Deep Java
Library — no Python), the same way `camera` nodes drive the `camera` clients. The
first is `ml/AnimalClassifierNode` (a squirrel/bird/other/none image classifier);
detectors and other model-backed nodes will join it. See
[integrations.md](integrations.md) for the model-inference story.

Add a new folder only when a node genuinely doesn't fit an existing category; if
you do, note it here.

## Display name

`getName()` returns `@Display.Name("…")` if present, else the simple class name.
Use `@Display.Name` for anything you want labeled nicely in the UI.

## Persisting node-specific config: `saveState` / `loadState`

Beyond input/output values, a node can persist a small `Map<String,String>` of
its own config (a dropdown choice, a chosen secret key) via `saveState()` /
`loadState()`. These are stored verbatim — **never put a secret in here** (store
the reference, not the value). Dynamic-port nodes persist enough state here to
rebuild their ports on load *before* values are applied (see `GraphFileIO`, which
calls `loadState` before touching ports).

## Dynamic-port nodes

Some nodes' ports depend on runtime wiring or settings:

- **`ObjectDecomposerNode`** grows one output per property of whatever type is
  wired into its `Object` input. It reacts via `onInputEdgeAdded` /
  `onInputEdgeRemoved`, derives properties with `ObjectProperties`, and persists
  the derived shape via `saveState`/`loadState` so it rebuilds on load
  independent of edge order.
- Slash-command nodes rebuild their ports from their declared options.

When writing a dynamic-port node: guard against reacting to the edge churn your
own `rebuildPorts()` causes (see `ObjectDecomposerNode`'s `refreshing` flag), and
persist enough in `saveState` to reconstruct ports deterministically.

## Recipe: add a new node

Minimal example — a node that multiplies two floats. Compare with
`graph/nodes/math/AddNode.java`.

```java
package io.github.jaymcole.housegraph.graph.nodes.math;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;

@Display.Name("Multiply")
public class MultiplyNode extends BaseNode {

    private final NodeVariable<Float> a = new NodeVariable<>("A", Float.class);
    private final NodeVariable<Float> b = new NodeVariable<>("B", Float.class);
    private final NodeVariable<Float> product = new NodeVariable<>("Product", Float.class);

    @Override public void process() {
        product.setValue(safe(a) * safe(b));
    }
    @Override public void configureInputs()  { addInput(a); addInput(b); }
    @Override public void configureOutputs() { addOutput(product); }
    @Override public void configureFlowInputs()  { addFlowInput(new FlowPort("", FlowPort.Direction.IN)); }
    @Override public void configureFlowOutputs() { addFlowOutput(new FlowPort("", FlowPort.Direction.OUT)); }

    private static float safe(NodeVariable<Float> v) {
        return v.getValue() == null ? 0f : v.getValue();
    }
}
```

That's the whole thing — no registration. Checklist:

1. Extend `BaseNode`; put it under `graph/nodes/<category>/`.
2. Declare ports in the `configure*` hooks; do the work in `process()`.
3. `@Display.Name("…")` for a nice menu/label name.
4. Optional: implement `NodeContentProvider` for inline UI (see [ui.md](ui.md)),
   override `saveState`/`loadState` for extra config, `activate()` for branching,
   `onActivated`/`onRemoved` for a long-lived resource.
5. Add a test mirroring `NodeGraphTest` / the existing node tests (see
   [testing.md](testing.md)).

---

**When you change this, update…** this file whenever you change the `BaseNode`
port model, the `NodeRegistry` discovery mechanism, the persistence rules, the
add-a-node recipe, or the set of node **categories**.
