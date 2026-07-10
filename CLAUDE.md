# CLAUDE.md — HouseGraph

This is the entry point for anyone (human or AI) working on HouseGraph. Read it
first. It is the map: it tells you what the app is, where each concern lives, the
standards the code holds itself to, and — critically — **the rule that changes
must keep documentation in sync**.

Deep-dive documentation lives under [`docs/architecture/`](docs/architecture/).
High-traffic packages also carry their own nested `CLAUDE.md`, which Claude Code
loads automatically when you edit files there.

---

## 📌 Documentation maintenance mandate (read this before you change anything)

**Any change that alters architecture, a public contract, a subsystem's
observable behavior, or the set of node types / editable types MUST update the
relevant documentation in the same change.** Docs that drift are worse than no
docs. This is not optional and applies to every contributor, including AI agents.

The code carries deliberate, explanatory Javadoc (it explains *why*, not just
*what*). Preserve that standard: when you touch a class, keep its Javadoc true.

Use this map of change → what to update:

| If you change… | Update… |
| --- | --- |
| Graph execution / threading / locking model | `NodeGraph` Javadoc **and** [`docs/architecture/graph-engine.md`](docs/architecture/graph-engine.md) |
| `BaseNode` lifecycle hooks or contract | `BaseNode` Javadoc **and** [`docs/architecture/nodes.md`](docs/architecture/nodes.md) |
| Add a node type | Nothing to register (discovery is automatic). If it introduces a **new category folder**, note the category in [`docs/architecture/nodes.md`](docs/architecture/nodes.md) |
| Make a new value type manually editable | `ValueEditors` static block **and** [`docs/architecture/ui.md`](docs/architecture/ui.md) |
| Save-file JSON format | `GraphFileIO` Javadoc **and** [`docs/architecture/ui.md`](docs/architecture/ui.md) (keep backward-compat notes) |
| Resource registry / pub-sub semantics | `ResourceRegistry` Javadoc **and** [`docs/architecture/resources.md`](docs/architecture/resources.md) |
| Secret storage / crypto / on-disk locations | `SecretsStore` / `AppDirectories` Javadoc **and** [`docs/architecture/storage-and-secrets.md`](docs/architecture/storage-and-secrets.md) |
| Logging levels / sinks / bootstrap / the log window | `LogManager` / `Logging` / `LogWindow` Javadoc **and** [`docs/architecture/logging.md`](docs/architecture/logging.md) |
| Discord / camera / IoT integration | [`docs/architecture/integrations.md`](docs/architecture/integrations.md) |
| Add a new package | Add a `package-info.java` for it |
| Anything user-facing (build, run, features) | `README.md` |

Every file under `docs/architecture/` ends with a **"When you change this,
update…"** note. Honor it.

---

## What HouseGraph is

A JavaFX desktop app: a **node-graph editor for home automation**, with a focus
on computer-vision triggers. You wire nodes on an infinite canvas — constants,
math, converters, control-flow branches, camera-motion sensors, Discord bots, an
Arduino "squirrel alarm" sign — into graphs that react to events. Graphs are
saved as JSON and reopened between sessions.

Two kinds of connection run between nodes, and keeping them separate is the
central design idea (see [Core standards](#core-architectural-standards)):

- **Data edges** carry a typed value from one node's output to another's input.
- **Flow edges** carry no value; they define execution order — *when* a node runs.

---

## Build & run

- **Java 21** toolchain (set via Gradle; you don't need it installed globally if
  Gradle can provision it).
- **JavaFX 21** via the `org.openjfx.javafxplugin` Gradle plugin
  (`javafx.controls`, `javafx.fxml`).

```bash
./gradlew run           # launch the app
./gradlew test          # run the JUnit 5 test suite
./gradlew compileJava   # compile only (fast sanity check for doc/Javadoc edits)
```

- **Entry points:** `Launcher` (the `main` you actually run) delegates to
  `App extends Application`. The split exists so JavaFX launches cleanly from a
  plain classpath jar — do not move `main` into `App`.
- **Key dependencies:** `net.dv8tion:JDA` (Discord gateway), `org.json` (save
  files, config, secrets blob), `slf4j-simple` (backs JDA's own logging; tuned
  down to warn in `src/main/resources/simplelogger.properties`). HouseGraph's own
  code logs through the in-house `logging/` package, not SLF4J — see
  [`docs/architecture/logging.md`](docs/architecture/logging.md).

---

## Architecture map

Dependencies point **downward**: the UI depends on the graph engine; the graph
engine depends on nothing above it and never imports JavaFX. Integration
subsystems depend on the engine, never the reverse.

```
        ┌────────────────────────────────────────────┐
        │  ui/         JavaFX canvas, views, editors, │
        │              undo, save/load                │
        └───────────────────┬────────────────────────┘
                            │ depends on
        ┌───────────────────▼────────────────────────┐
        │  graph/      execution engine + node model  │
        │  graph/nodes/  the node library (by folder) │
        └──────┬───────────────┬──────────────┬───────┘
               │               │              │
   ┌───────────▼──┐  ┌─────────▼───────┐  ┌───▼─────────────┐
   │ resource/    │  │ storage/        │  │ discord/ camera/│
   │ name-keyed   │  │ dirs, secrets,  │  │ integration     │
   │ lookup+events│  │ preferences     │  │ clients         │
   └──────────────┘  └─────────────────┘  └─────────────────┘

   logging/  — cross-cutting; depends on nothing, so any layer may log
```

| Concern | Package | Deep dive |
| --- | --- | --- |
| App lifecycle / entry points | `io.github.jaymcole.housegraph` (`App`, `Launcher`) | [overview.md](docs/architecture/overview.md) |
| Execution engine (resolve/execute, threading) | `graph` (`NodeGraph`, `NodeProcessingStatus`, `GraphExecutionListener`) | [graph-engine.md](docs/architecture/graph-engine.md) |
| Node model & discovery | `graph` (`BaseNode`, `NodeVariable`, `Edge`, `FlowEdge`, `FlowPort`, `NodeRegistry`), `annotations` | [nodes.md](docs/architecture/nodes.md) |
| The node library | `graph.nodes.*` (one folder per category) | [nodes.md](docs/architecture/nodes.md) |
| Canvas, views, undo, save/load | `ui` | [ui.md](docs/architecture/ui.md) |
| Named resources & event pub/sub | `resource` | [resources.md](docs/architecture/resources.md) |
| On-disk locations, secrets, preferences | `storage` | [storage-and-secrets.md](docs/architecture/storage-and-secrets.md) |
| Logging (levels, sinks, the log window) | `logging`, `ui.log` | [logging.md](docs/architecture/logging.md) |
| Discord / cameras / Arduino IoT | `discord`, `camera`, `extras/squirrel_status` | [integrations.md](docs/architecture/integrations.md) |
| Tests | `src/test/...` | [testing.md](docs/architecture/testing.md) |

---

## Core architectural standards

These are not aspirational — they describe how the code already works. Uphold
them.

1. **Data and flow are separate concepts.** A `NodeVariable`/`Edge` moves a typed
   value; a `FlowPort`/`FlowEdge` moves control with no value. Never fold one
   into the other. Data is *pulled* (a node resolves its inputs on demand); flow
   is *pushed* (a trigger cascades downstream). See [graph-engine.md](docs/architecture/graph-engine.md).

2. **The engine is threaded, and `NodeGraph` never imports JavaFX.** Top-level
   passes run on a single serialized execution thread; sibling flow branches fan
   out onto virtual threads; each node's resolution is guarded by its own
   intrinsic lock (which also detects data cycles). UI callbacks are dispatched
   through an injectable **callback executor** (`Platform::runLater` in the app,
   `Runnable::run` in tests). If you add a callback into the UI, route it through
   that seam — don't import JavaFX into `graph`.

3. **Nodes never persist computed or secret values.** Only manually-authored,
   non-secret, non-transient values are written to save files
   (`NodeVariable.isPersistentValue`). Mark sensitive fields with `markSecret()`
   and live runtime handles with `transientValue()`. Computed outputs are
   recomputed on load, never stored.

4. **Long-lived resources are referenced by name, not wired.** A resource node
   (a Discord bot, an echo channel) registers itself in `ResourceRegistry` under
   a user-chosen name; action and trigger nodes look it up / subscribe by that
   name. Liveness is user-driven (a Connect button), not tied to being on the
   canvas — open connections in response to user action, tear them down in
   `onRemoved()`. See [resources.md](docs/architecture/resources.md).

5. **Secrets live only in `SecretsStore` (encrypted).** Never write a credential
   to a save file, the camera registry, or any plaintext config. Nodes store a
   *reference* to a secret (its key), and resolve the value at runtime.

6. **All on-disk paths go through `AppDirectories`.** Never hardcode a home-dir
   path or an OS-specific location; ask `AppDirectories` for the right folder.

7. **Keep pure logic headless-testable.** `NodeGraph`, `GraphFileIO`'s JSON
   conversion, and `ObjectProperties` deliberately avoid JavaFX so they can be
   unit-tested without a display. Preserve that separation when extending them.

---

## How to extend (common recipes)

- **Add a node type** → subclass `BaseNode`, drop it under
  `graph/nodes/<category>/`, annotate `@Display.Name("…")`. It appears in the
  Add-Node menu automatically (classpath discovery — no registration).
  Full recipe: [nodes.md](docs/architecture/nodes.md).
- **Give a node its own inline UI** → implement `NodeContentProvider`; push fresh
  values from `onExecuted()`. See [ui.md](docs/architecture/ui.md).
- **Make a new type manually editable** → add one line to the `ValueEditors`
  static block. See [ui.md](docs/architecture/ui.md).
- **Add a named-resource integration** → follow the Discord-bot pattern in
  [resources.md](docs/architecture/resources.md).
