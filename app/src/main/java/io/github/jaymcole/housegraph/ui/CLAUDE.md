# `ui/` — the JavaFX layer

Full context: [`docs/engine/ui-layer.md`](../../../../../../../../../docs/engine/ui-layer.md).
Start at the repo-root [`CLAUDE.md`](../../../../../../../../../CLAUDE.md) if you
haven't.

This is the only package that owns JavaFX-thread concerns. It sits at the top of
the dependency stack: it depends on `graph/` and below, never the reverse.

The window this package fills is `GraphWindow`, outside it — one per open graph, each
with its own `GraphCanvas` and `MainMenuBar` over shared services owned by `App`. See
[`docs/engine/windows.md`](../../../../../../../../../docs/engine/windows.md).

## Layout

`GraphCanvas` is the hub and stays at the package root, alongside
`ModuleReferenceAction` — the host's side of Add Module…, for the same reason
`menu/MenuActions` exists. Everything else is split by concern.

| Sub-package | Holds |
| --- | --- |
| `view/` | node/edge/port views, `GroupView`, and the `ExecutionPolicyIcons` glyphs |
| `editor/` | the secrets dialog (`SecretsEditor`) |
| `command/` | undo/redo — `Command`, `UndoManager`, every `*Command` |
| `io/` | the canvas-facing save/load wrappers (`GraphFileIO`) |
| `log/` | the log viewer (`LogWindow`) and `LogLevelPreferences` |
| `menu/` | the application menu bar (`MainMenuBar`) and the `MenuActions` each editor window implements |
| `plugin/` | the node-library manager (`PluginWindow`) |
| `module/` | the module picker (`ModulePickerDialog`) |
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
- **Modules decide nothing here.** `modules/` is headless and tested: what may be
  published (`ModulePublisher`), what the picker offers (`ModuleChoices`), and how a
  loaded module reference is resolved (`ModuleBinding`). `ui/module/` renders, and
  `GraphWindow` runs both filesystem actions on a worker. `GraphCanvas.loadSnapshot`
  calls `ModuleBinding` after `place` and before `resumeRunningNodes`, and never from
  `place` itself — a rebuilt shape swaps out the `NodeView` a paste `Command` holds.
  See [`docs/engine/ui-layer.md`](../../../../../../../../../docs/engine/ui-layer.md).
- **Reversible canvas mutations are `Command`s.** Anything undoable goes through
  `UndoManager` as a `Command`, not an ad-hoc mutation. Use `record()` for gestures
  applied live, such as a drag, that become one undo step at the end.
- **Group frames decide nothing here either.** What a frame commands, how frames
  nest, and what order they paint in are all `saveformat/NodeGroup`, which is
  headless and unit-tested. `GroupView` draws a frame and reports its gestures;
  `GraphCanvas` applies them to whatever the rules say is inside. Never store what a
  frame contains — membership is recomputed from the rectangle, which is why nothing
  has to be kept in step with the canvas.
- **Save/load logic is headless, and lives outside this package.**
  `saveformat.GraphFileIO`'s `toJson`/`fromJson` (and the `GraphSnapshot` shape they
  read and write) are free of JavaFX so they can be unit-tested, and live in their
  own headless package for the same reason `loader/GraphLoader` does — a headless
  caller (`cli/`, `remote/`, `catalog/`, `headless/`) cannot reach up into `ui/` to
  parse a save file. `ui.io.GraphFileIO`'s `save`/`load` are the only two
  canvas-facing methods here: thin wrappers pulling a snapshot and camera state off a
  real `GraphCanvas` and handing them to `saveformat/`. The one decision it makes on
  its own — what an unreadable file means for the module identity it was about to
  carry across — takes a `File` and is tested headlessly, like `RecentGraphs`. The other half of *opening* a graph is
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

**When you change canvas interaction, views, commands, editors, the module surface,
or either auxiliary window, update
[`docs/engine/ui-layer.md`](../../../../../../../../../docs/engine/ui-layer.md).**
