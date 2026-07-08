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
  several named ports and picks with `activate()`.

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

Both `markSecret()` and `transientValue()` are fluent, for field initialization.

### Execution policy

Every node carries an `ExecutionPolicy` (`getExecutionPolicy()`/`setExecutionPolicy()`,
default `QUEUE`, persisted by `GraphFileIO`) that governs what happens when the node is
**triggered again while a pass it started is still in flight** — drop it, restart, or
queue (coalesced). The engine, not the node, enforces it — see [graph-engine.md](graph-engine.md).

### Flow joins (AND-barriers)

Because a run is fire-and-forget (see [graph-engine.md](graph-engine.md)), an ordinary node
reached by several flow branches fires on the **first** arrival (OR/merge). A node that overrides
`BaseNode.isFlowJoin()` to return `true` instead waits for **all** its wired incoming flow edges
before firing (AND) — the way to reconverge parallel branches. `graph/nodes/control/JoinNode.java`
is the concrete node (numbered flow-in ports, adjustable 2–8). The engine counts arrivals per run
(`ExecutionContext`) and fires the join once they reach its wired-edge count; an unwired port
doesn't count, and a join whose branch is pruned by an upstream `If` just doesn't fire that run.

### Execution policy

It only matters for **execution entry points** — nodes `execute()` is called on directly
(a trigger button, a timer, an inbound event), as opposed to nodes only reached along a
flow edge. `BaseNode.isExecutionEntryPoint()` marks these: it defaults to "has a flow-out
but no flow-in" (such a node can only ever run via a direct `execute()`), which covers the
usual trigger/listener sources automatically. A node that self-triggers *and* has a flow-in
(e.g. `DiscoverCamerasNode`'s Discover button) overrides it to `true`. The UI shows the
policy glyph and right-click policy submenu only for entry points; the field is inert elsewhere.

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
  same list shape for a given class).

## Node categories (current folders under `graph/nodes/`)

`camera`, `constants`, `control`, `converters`, `debug`, `discord`, `iot`,
`loader`, `math`, `object`, `resource`, `viewers`.

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
