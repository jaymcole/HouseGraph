# Architecture Overview

HouseGraph is a JavaFX desktop app for building **home-automation graphs**. You
place nodes on an infinite canvas, wire them together, and the graph reacts to
events (a camera detecting motion, a Discord command, a timer). This document is
the orientation layer; each subsystem has its own deep-dive, linked below.

> New here? Read the root [`CLAUDE.md`](../../CLAUDE.md) first — it has the
> architecture map, the build/run commands, and the core standards.

## Subsystem deep-dives

- [graph-engine.md](graph-engine.md) — how a graph executes (resolve vs. execute,
  threading, cycle detection).
- [nodes.md](nodes.md) — the node model, discovery, and how to add a node.
- [ui.md](ui.md) — canvas, views, undo, editing, save/load.
- [resources.md](resources.md) — named resources and event pub/sub.
- [storage-and-secrets.md](storage-and-secrets.md) — on-disk layout, encrypted
  secrets, preferences.
- [integrations.md](integrations.md) — Discord, cameras, the Arduino IoT device.
- [testing.md](testing.md) — test conventions and the headless-testability rule.

## Layering and dependency direction

Dependencies point **downward**. Nothing in a lower layer knows about a higher
one.

```
ui/  ──────────────►  graph/ (engine + node model)  ──────►  resource/
                             ▲                                storage/
                             │                        ◄──────  discord/ camera/
             graph/nodes/ ───┘   (nodes depend on engine +
                                  the subsystems they integrate)
```

- **`graph/` (the engine) never imports JavaFX.** It reaches the UI only through
  an injected callback executor and the `GraphExecutionListener` interface. This
  is what keeps the engine headless-testable and is a hard rule — see
  [graph-engine.md](graph-engine.md).
- **`ui/` orchestrates everything else.** `GraphCanvas` owns a `NodeGraph`,
  renders `NodeView`s, wires user gestures to engine calls, and drives save/load.
- **`graph/nodes/`** subclasses depend on the engine and, for integration nodes,
  on `resource/`, `storage/`, `discord/`, or `camera/`. The reverse never holds.

## The main objects at a glance

| Object | Role |
| --- | --- |
| `NodeGraph` | Owns nodes + edges; drives execution. One instance per document. |
| `BaseNode` | Abstract base every node extends. Declares inputs/outputs/flow ports and a `process()`. |
| `NodeVariable<T>` | A typed data slot (input or output) on a node. |
| `Edge` | A data connection: source output → target input. |
| `FlowPort` / `FlowEdge` | A control-flow anchor / connection (no data). |
| `NodeRegistry` | Discovers node classes on the classpath; instantiates/duplicates them. |
| `GraphCanvas` | The JavaFX canvas hosting node/edge views and user interaction. |
| `ResourceRegistry` | App-wide, name-keyed lookup + event pub/sub for long-lived resources. |
| `SecretsStore` / `AppDirectories` | Encrypted secrets / OS-appropriate file locations. |

## Lifecycle of a graph

1. **Launch.** `Launcher.main` → `App.start` builds a `NodeGraph` and a
   `GraphCanvas`, wires the toolbar (Save / Load / Secrets), and reopens the last
   file recorded in `AppPreferences`.
2. **Edit.** The user adds nodes (from the classpath-discovered Add-Node menu),
   drags data edges between ports and flow edges between the triangular anchors,
   types values into editable fields, and moves/copies/deletes — all tracked for
   undo (see [ui.md](ui.md)).
3. **Run.** A trigger node (a button, a timer, an incoming event) calls
   `execute()`, which resolves the node's data inputs and then cascades control
   along flow edges. A node that only needs a value calls `beginProcessing()` to
   *pull* without cascading. See [graph-engine.md](graph-engine.md).
4. **Save / Load.** `GraphFileIO` serializes the canvas to JSON (nodes by type +
   position + persistent values + per-node `saveState`, edges by index) and
   restores it. Computed and secret values are never written.
5. **Shutdown.** `App.stop` calls `NodeGraph.dispose()`, which removes every node
   (firing `onRemoved()` so timers/sockets/threads are released) and stops the
   execution threads.

---

**When you change this, update…** this file whenever you add or remove a
subsystem/package, change the dependency direction between layers, or alter the
launch/save/shutdown lifecycle.
