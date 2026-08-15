# Architecture

HouseGraph is two Gradle modules. Dependencies point downward; nothing in a lower
layer knows about a higher one.

```
┌─ app/ ───────────────────────────────────────────────────┐
│   ui/            JavaFX canvas, views, editors, undo,     │
│                  save/load, the log and library windows   │
│        │                                                  │
│   graph/nodes/   the built-in node library                │
│   plugin/        host side of out-of-tree libraries       │
│   cli/ remote/   headless CLI, git sync, supervision      │
└────────│──────────────────────────────────────────────────┘
         │ depends on
┌─ housegraph-api/ ─────────────────────────────────────────┐
│   graph/         execution engine + node model            │
│   sdk/           node-authoring extension points          │
│   annotations/   @Node.Type, @Display                     │
│   resource/      name-keyed lookup + event pub/sub        │
│   storage/ store/  directories, secrets, preferences      │
│   logging/       depends on nothing                       │
└───────────────────────────────────────────────────────────┘
```

Out-of-tree node libraries sit beside `app`, depending only on `housegraph-api`.

| Module | Contains | Published |
| --- | --- | --- |
| `housegraph-api` | `graph/`, `sdk/`, `annotations/`, `logging/`, `resource/`, `storage/`, `store/` | Yes — node libraries compile against it |
| `app` | `ui/`, `App`/`Launcher`, `graph/nodes/`, `plugin/`, `cli/`, `remote/` | No |

`graph/` is in the api module while `graph/nodes/` is in `app`. Distinct packages,
not a split package.

## Layer rules

- **`graph/` never imports JavaFX.** It reaches the UI through an injected
  callback executor and the `GraphExecutionListener` interface. See
  [execution-model.md](execution-model.md).
- **`ui/` orchestrates.** `GraphCanvas` owns a `NodeGraph` and a `NodeRegistry`,
  renders views, wires gestures to engine calls, and drives save/load.
- **`graph/nodes/`** holds dependency-free primitives only. Every integration
  category is an out-of-tree library.
- **`app/plugin/`, `app/cli/` and `app/remote/` are headless.** This repository
  has no way to test a window, so nothing worth testing may live in one.
  `remote/` supervises the JavaFX app as a child process rather than running
  graphs itself.

## Principal types

| Type | Role |
| --- | --- |
| `NodeGraph` | Owns nodes and edges; drives execution. One per document. |
| `BaseNode` | Base class every node extends. Declares ports and a `process(ProcessContext)`. |
| `NodeVariable<T>` | A typed data slot (input or output). |
| `ProcessContext` | Per-invocation handle: cooperative cancellation, null-safe value access. |
| `Edge` | A data connection: source output → target input. |
| `FlowPort` / `FlowEdge` | A control-flow anchor / connection, carrying no value. |
| `NodeRegistry` | Discovers node classes across `ScanRoot`s; instantiates and duplicates them. |
| `MissingNode` | Placeholder for a node whose library isn't installed, preserving it verbatim. |
| `PluginCatalog` / `PluginLoader` | What is installed, and the shared class loader serving it. |
| `GraphCanvas` | The JavaFX canvas hosting node and edge views. |
| `ResourceRegistry` | App-wide, name-keyed lookup and event pub/sub. |
| `SecretsStore` / `AppDirectories` | Encrypted secrets / OS-appropriate file locations. |
| `LogManager` / `Logger` | Process-wide log hub, fanning out to level-filtered sinks. |

## Entry points

`Launcher` holds the `main` that is actually run and delegates to
`App extends Application`. The split exists so JavaFX launches cleanly from a
plain classpath jar; do not move `main` into `App`.

`Launcher` forks on the first argument. A bare word is a CLI command and never
touches JavaFX, except `run`, which falls through because opening a graph is the
GUI. Anything else, including no arguments, launches the window. See
[remote-runtime.md](remote-runtime.md).

## Application lifecycle

1. **Launch.** `App.start` bootstraps logging, loads the node-library catalog and
   prunes superseded versions, builds a `PluginLoader` and installs it as the
   thread's context class loader, then builds a `NodeGraph` and a `NodeRegistry`
   scanning the built-in library plus every installed one. It wires the toolbar
   and reopens the last file from `AppPreferences`, or the one named by `--graph`.
   No startup path makes a network call.
2. **Edit.** Nodes are added from the Add-Node menu, edges dragged, values typed —
   all tracked for undo. See [ui-layer.md](ui-layer.md).
3. **Run.** A trigger node calls `execute()`, which resolves its data inputs and
   cascades along flow edges. A node that only needs a value calls
   `beginProcessing()` to pull without cascading. See
   [execution-model.md](execution-model.md).
4. **Save / load.** `GraphFileIO` serializes to JSON and restores. Computed and
   secret values are never written. A node whose library isn't installed loads as
   a `MissingNode`. See [save-format.md](save-format.md).
5. **Shutdown.** `App.stop` calls `NodeGraph.dispose()`, closes the node-library
   class loader, then flushes and closes the log file. A shutdown hook routes a
   signalled JVM through the same path, because JavaFX calls `stop()` on a
   platform exit but not on a signal. See [node-lifecycle.md](node-lifecycle.md).

---

**When you change this, update…** this file whenever you add or remove a
package, change the dependency direction between layers or modules, or alter the
launch/save/shutdown lifecycle.
