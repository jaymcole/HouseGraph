# Architecture

HouseGraph is two Gradle modules. Dependencies point downward; nothing in a lower
layer knows about a higher one.

```
┌─ app/ ───────────────────────────────────────────────────┐
│   ui/            JavaFX canvas, views, editors, undo,     │
│                  the log and library windows              │
│        │                                                  │
│   saveformat/    the graph JSON format: file ↔ snapshot   │
│   loader/        a saved snapshot → a live NodeGraph      │
│   headless/      one graph, running, with no window       │
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
| `app` | `ui/`, `App`/`Launcher`, `graph/nodes/`, `saveformat/`, `loader/`, `headless/`, `plugin/`, `search/`, `cli/`, `remote/` | No |

`graph/` is in the api module while `graph/nodes/` is in `app`. Distinct packages,
not a split package.

## Layer rules

- **`graph/` never imports JavaFX.** It reaches the UI through an injected
  callback executor and the `GraphExecutionListener` interface. See
  [execution-model.md](execution-model.md).
- **`ui/` orchestrates.** `GraphCanvas` owns a `NodeGraph` and a `NodeRegistry`,
  renders views, wires gestures to engine calls, and calls thin save/load wrappers
  over `saveformat/`.
- **`graph/nodes/`** holds dependency-free primitives only. Every integration
  category is an out-of-tree library.
- **`app/saveformat/`, `app/loader/`, `app/headless/`, `app/plugin/`, `app/cli/` and
  `app/remote/` are headless.** This repository has no way to test a window, so
  nothing worth testing may live in one. `remote/` supervises a child process
  rather than running graphs itself; `headless/` is what such a child can be, and
  is a package rather than part of `remote/` so the supervisor does not depend on
  the thing it supervises.
- **The save format is not a canvas operation.** `saveformat/GraphFileIO` converts
  a `GraphSnapshot` (and a `CameraState`) to a save file's JSON and back, with no
  view involved; `ui/io/GraphFileIO`'s `save`/`load` are the thin wrappers that
  pull a snapshot and camera state off a real `GraphCanvas` and hand them in. It
  sits in its own package rather than in `ui/` because its callers — `cli/`,
  `remote/`, `catalog/` and `headless/` — are below the UI, and a downward
  dependency is the only kind allowed. `CameraState` moved with it, even though a
  canvas's pan/zoom is also a view concept, because `cameraFromJson` returns it and
  `toJson` takes it as a parameter; `GraphCanvas` imports it downward the same way
  it imports `GraphSnapshot`.
- **Opening a graph is not a canvas operation.** `GraphLoader` builds a
  `saveformat.GraphSnapshot`'s nodes and edges onto a `NodeGraph` with no view
  involved; `GraphCanvas.place` calls it and then draws the result. It sits in its
  own package rather than in `ui/` because its callers — `headless/`, `cli/`,
  `remote/`, and a node that loads another graph — are below the UI, and a
  downward dependency is the only kind allowed.
- **Running a graph is not a canvas operation either.** `headless/HeadlessRunner`
  opens a graph, resumes its `AutoStartable` nodes and stays up with no toolkit
  started. See [remote-runtime.md](remote-runtime.md).

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
| `NodeMetadata` | What a node type declares about itself: description, keywords, `NodeKind`. |
| `NodeSearchIndex` | Ranked search over the discovered node types. |
| `MissingNode` | Placeholder for a node whose library isn't installed, preserving it verbatim. |
| `PluginCatalog` / `PluginLoader` | What is installed, and the shared class loader serving it. |
| `GraphLoader` | Builds a `saveformat.GraphSnapshot`'s nodes and edges onto a `NodeGraph`, headlessly. |
| `HeadlessRunner` | Runs one graph with no window, until the process is signalled. |
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
GUI. Anything else, including no arguments, launches the window. `run` forks once
more, on `--headless`, into `HeadlessRunner`. See
[remote-runtime.md](remote-runtime.md).

## Application lifecycle

1. **Launch.** `App.start` bootstraps logging, loads the node-library catalog and
   prunes superseded versions, builds a `PluginLoader` and installs it as the
   thread's context class loader, then builds a `NodeGraph` and a `NodeRegistry`
   scanning the built-in library plus every installed one. It builds the menu bar
   and toolbar — `App` is the `MenuActions` behind the menus — and reopens the last
   file from `AppPreferences`, or the one named by `--graph`.
   No startup path makes a network call.
2. **Edit.** Nodes are added from the Add-Node menu, edges dragged, values typed —
   all tracked for undo. See [ui-layer.md](ui-layer.md).
3. **Run.** A trigger node calls `execute()`, which resolves its data inputs and
   cascades along flow edges. A node that only needs a value calls
   `beginProcessing()` to pull without cascading. See
   [execution-model.md](execution-model.md).
4. **Save / load.** `GraphFileIO` serializes to JSON and parses it back to a
   `GraphSnapshot`; `GraphLoader` turns that into live nodes and edges. Computed and
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
