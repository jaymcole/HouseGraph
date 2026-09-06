# `ui/` — the JavaFX layer

Full context: [`docs/engine/ui-layer.md`](../../../../../../../../../docs/engine/ui-layer.md).
Start at the repo-root [`CLAUDE.md`](../../../../../../../../../CLAUDE.md) if you
haven't.

This is the only package that owns JavaFX-thread concerns. It sits at the top of
the dependency stack: it depends on `graph/` and below, never the reverse.

## Layout

`GraphCanvas` is the hub and stays at the package root; everything else is split by
concern.

| Sub-package | Holds |
| --- | --- |
| `view/` | node/edge/port views and the `ExecutionPolicyIcons` glyphs |
| `editor/` | the secrets dialog (`SecretsEditor`) |
| `command/` | undo/redo — `Command`, `UndoManager`, every `*Command` |
| `io/` | the canvas-facing save/load wrappers (`GraphFileIO`) |
| `log/` | the log viewer (`LogWindow`) and `LogLevelPreferences` |
| `menu/` | the application menu bar (`MainMenuBar`) and the `MenuActions` the host app implements |
| `plugin/` | the node-library manager (`PluginWindow`) |
| `export/` | rendering the canvas to PNGs, one per connected component |
| `widget/` | small controls with no graph-model dependency, reused across windows (`TaskProgressBar`) |

Because these are separate packages, the cross-package API each exposes is
`public`; keep genuinely package-local helpers package-private. Prefer standalone
files over public nested types for anything shared across packages — that is why
the snapshot records (`GraphSnapshot`, `ClipboardNode`, `ClipboardDataEdge`,
`ClipboardFlowEdge`, `CameraState`) live in the headless `saveformat/` package
outside `ui/` entirely, rather than inside `GraphCanvas` or even in a sub-package
here — see [save-format.md](../../../../../../../../../docs/engine/save-format.md).
A `private` nested helper used in one file is fine. Put new files in the
sub-package matching their concern, and mirror them under the matching test
package.

## Hold these when editing here

- **All view code runs on the FX Application Thread.** The engine marshals its
  callbacks through its callback executor, which `GraphCanvas` sets to
  `Platform::runLater`. Never call into JavaFX from an engine thread, and never do
  blocking work on the FX thread — use a worker, then `Platform.runLater` the UI
  update. `PluginWindow`'s install flow is the in-tree example.
- **Reversible canvas mutations are `Command`s.** Anything undoable goes through
  `UndoManager` as a `Command`, not an ad-hoc mutation. Use `record()` for gestures
  applied live, such as a drag, that become one undo step at the end.
- **Save/load logic is headless, and lives outside this package.**
  `saveformat.GraphFileIO`'s `toJson`/`fromJson` (and the `GraphSnapshot` shape they
  read and write) are free of JavaFX so they can be unit-tested, and live in their
  own headless package for the same reason `loader/GraphLoader` does — a headless
  caller (`cli/`, `remote/`, `catalog/`, `headless/`) cannot reach up into `ui/` to
  parse a save file. `ui.io.GraphFileIO`'s `save`/`load` are the only two methods
  here: thin wrappers pulling a snapshot and camera state off a real `GraphCanvas`
  and handing them to `saveformat/`. The other half of *opening* a graph is
  `loader/GraphLoader`: it turns a `GraphSnapshot` into live nodes and edges on the
  `NodeGraph` with no view involved, and `GraphCanvas.place` is only the drawing on
  top of it. When you change the JSON format, keep the forgiving-read behaviour and
  update the `saveformat.GraphFileIO` Javadoc **and**
  [`docs/engine/save-format.md`](../../../../../../../../../docs/engine/save-format.md).
- **The node-facing extension points are not here.** `NodeContentProvider`,
  `AutoStartable`, `NodePresentation`, `NodeTimer` and `ValueEditors` live in `sdk/`
  in `housegraph-api`, because out-of-tree nodes cannot see `app`. This layer only
  consumes them.
- **`NodeView` is what gives a node a view.** `addNodeView` attaches a
  `NodePresentation` and `removeNodeView` clears it, so `BaseNode.present(...)`
  reaches controls exactly while they are on the canvas — the constructor attaches
  once more before `createNodeContent()`, and `addNodeView` re-attaches because
  undoing a delete re-adds the same view object. That sink is the only place
  `Platform.runLater` belongs for node UI; a node never writes a control directly
  and never asks whether the process is headless.
- **New manually-editable type?** Add one line to the `sdk.ValueEditors` static
  block; nothing in `PortView` changes. Note it in
  [`docs/engine/type-system.md`](../../../../../../../../../docs/engine/type-system.md).
- **Keep the component split headless.** `export/GraphComponents` is plain graph
  logic and is unit-tested without a display; only `GraphImageExport` touches
  pixels. A single whole-canvas `snapshot` is not an option — see the image-export
  section of
  [`docs/engine/ui-layer.md`](../../../../../../../../../docs/engine/ui-layer.md).

**When you change canvas interaction, views, commands, editors, or either
auxiliary window, update
[`docs/engine/ui-layer.md`](../../../../../../../../../docs/engine/ui-layer.md).**
