# Architecture Overview

HouseGraph is a JavaFX desktop app for building **home-automation graphs**. You
place nodes on an infinite canvas, wire them together, and the graph reacts to
events (a camera detecting motion, an incoming chat command, a timer). Beyond a
set of dependency-free primitives, node types are not built in — they come from
**node libraries**, fetched at runtime from a GitHub repository the user adds.
This document is the orientation layer; each subsystem has its own deep-dive,
linked below.

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
- [logging.md](logging.md) — log levels, the console/file/buffer sinks, and the
  standalone log window.
- [integrations.md](integrations.md) — local web hosting, local ML inference, and
  other integrations still built into this repository.
- [plugins.md](plugins.md) — the module split, out-of-tree node libraries, how
  they're fetched and loaded, and the extraction status of each integration.
- [testing.md](testing.md) — test conventions and the headless-testability rule.

## Modules, layering, and dependency direction

The repository is two Gradle modules. `housegraph-api` is published — an
out-of-tree node library compiles against it and nothing else. `app` is the
desktop program; nobody compiles against it. Dependencies point **downward**
within each; nothing in a lower layer knows about a higher one.

```
app/            ui/  ──────────────►  graph/nodes/ (built-in library)
                                              │
                                              ▼ depends on
housegraph-api/ graph/ (engine + node model)  ──────►  resource/
                             ▲                          storage/ store/
                             │                 ◄──────  annotations/  sdk/
             app/plugin/  ───┘   (host-side loader for out-of-tree
                                  node libraries; never published)
```

- **`graph/` (the engine) never imports JavaFX.** It reaches the UI only through
  an injected callback executor and the `GraphExecutionListener` interface. This
  is what keeps the engine headless-testable and is a hard rule — see
  [graph-engine.md](graph-engine.md).
- **`ui/` orchestrates everything else.** `GraphCanvas` owns a `NodeGraph` and a
  `NodeRegistry`, renders `NodeView`s, wires user gestures to engine calls, and
  drives save/load.
- **`graph/nodes/`** subclasses depend on the engine and, for integration nodes
  still in this repo, on `resource/`, `storage/`, or `web/`. An out-of-tree
  node library depends only on `housegraph-api` — never on `app`.
- **`app/plugin/`** is the host side of loading out-of-tree libraries (manifest
  reading, cataloguing, fetching, class loading). It is not published; a node
  library never sees it. See [plugins.md](plugins.md).

## The main objects at a glance

| Object | Role |
| --- | --- |
| `NodeGraph` | Owns nodes + edges; drives execution. One instance per document. |
| `BaseNode` | Abstract base every node extends. Declares inputs/outputs/flow ports and a `process(ProcessContext)`. |
| `NodeVariable<T>` | A typed data slot (input or output) on a node. |
| `ProcessContext` | Per-invocation handle passed to `process()`: cooperative cancellation + null-safe value access. |
| `Edge` | A data connection: source output → target input. |
| `FlowPort` / `FlowEdge` | A control-flow anchor / connection (no data). |
| `NodeRegistry` | Discovers node classes across one or more `ScanRoot`s (the built-in library plus any installed node library); instantiates/duplicates them; tracks which library owns each type. |
| `MissingNode` | Stands in for a node type whose library isn't installed, preserving it verbatim so a save/load round trip never loses it. |
| `PluginCatalog` / `PluginLoader` | What node libraries are installed, and the shared class loader serving their classes. |
| `GraphCanvas` | The JavaFX canvas hosting node/edge views and user interaction. |
| `ResourceRegistry` | App-wide, name-keyed lookup + event pub/sub for long-lived resources. |
| `SecretsStore` / `AppDirectories` | Encrypted secrets / OS-appropriate file locations. |
| `LogManager` / `Logger` | Process-wide log hub + the front-end code logs through. Fans out to level-filtered sinks (console, file, in-memory window buffer). |

## Lifecycle of a graph

1. **Launch.** `Launcher.main` → `App.start` stands up logging
   (`Logging.bootstrap`), loads the node-library catalog and prunes superseded
   versions (purely local — no network on any startup path), builds a
   `PluginLoader` and installs it as the thread's context class loader, then
   builds a `NodeGraph` and a `NodeRegistry` scanning the built-in library plus
   every installed one. It wires the toolbar (Save / Load / Secrets / Logs /
   Node Libraries) and reopens the last file recorded in `AppPreferences`.
2. **Edit.** The user adds nodes (from the Add-Node menu, discovered across the
   built-in library and installed node libraries), drags data edges between
   ports and flow edges between the triangular anchors, types values into
   editable fields, and moves/copies/deletes — all tracked for undo (see
   [ui.md](ui.md)).
3. **Run.** A trigger node (a button, a timer, an incoming event) calls
   `execute()`, which resolves the node's data inputs and then cascades control
   along flow edges. A node that only needs a value calls `beginProcessing()` to
   *pull* without cascading. See [graph-engine.md](graph-engine.md).
4. **Save / Load.** `GraphFileIO` serializes the canvas to JSON (nodes by type +
   position + persistent values + per-node `saveState`, edges by index, plus a
   root table of the node libraries the graph depends on) and restores it.
   Computed and secret values are never written. A node whose library isn't
   installed loads as a `MissingNode` — preserved exactly, not dropped — and the
   load-time dependency check offers to install what's missing before the graph
   opens interactively.
5. **Shutdown.** `App.stop` calls `NodeGraph.dispose()`, which removes every node
   (firing `onRemoved()` so timers/sockets/threads are released) and stops the
   execution threads, closes the node-library class loader, then
   `Logging.shutdown()` to flush and close the log file.

---

**When you change this, update…** this file whenever you add or remove a
subsystem/package, change the dependency direction between layers or modules, or
alter the launch/save/shutdown lifecycle.
