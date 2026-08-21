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
| `snapshot/` | the snapshot data model, shared by copy/paste and save/load |
| `io/` | save/load (`GraphFileIO`) |
| `log/` | the log viewer (`LogWindow`) and `LogLevelPreferences` |
| `plugin/` | the node-library manager (`PluginWindow`) |
| `widget/` | small controls with no graph-model dependency, reused across windows (`TaskProgressBar`) |

Because these are separate packages, the cross-package API each exposes is
`public`; keep genuinely package-local helpers package-private. Prefer standalone
files over public nested types for anything shared across packages — that is why
the snapshot records live in `snapshot/` rather than inside `GraphCanvas`. A
`private` nested helper used in one file is fine. Put new files in the sub-package
matching their concern, and mirror them under the matching test package.

## Hold these when editing here

- **All view code runs on the FX Application Thread.** The engine marshals its
  callbacks through its callback executor, which `GraphCanvas` sets to
  `Platform::runLater`. Never call into JavaFX from an engine thread, and never do
  blocking work on the FX thread — use a worker, then `Platform.runLater` the UI
  update. `PluginWindow`'s install flow is the in-tree example.
- **Reversible canvas mutations are `Command`s.** Anything undoable goes through
  `UndoManager` as a `Command`, not an ad-hoc mutation. Use `record()` for gestures
  applied live, such as a drag, that become one undo step at the end.
- **Keep save/load logic headless.** `GraphFileIO`'s `toJson`/`fromJson` must stay
  free of JavaFX so they can be unit-tested; only `save`/`load` touch a canvas.
  When you change the JSON format, keep the forgiving-read behaviour and update the
  `GraphFileIO` Javadoc **and**
  [`docs/engine/save-format.md`](../../../../../../../../../docs/engine/save-format.md).
- **The node-facing extension points are not here.** `NodeContentProvider`,
  `AutoStartable` and `ValueEditors` live in `sdk/` in `housegraph-api`, because
  out-of-tree nodes cannot see `app`. This layer only consumes them.
- **New manually-editable type?** Add one line to the `sdk.ValueEditors` static
  block; nothing in `PortView` changes. Note it in
  [`docs/engine/type-system.md`](../../../../../../../../../docs/engine/type-system.md).

**When you change canvas interaction, views, commands, editors, or either
auxiliary window, update
[`docs/engine/ui-layer.md`](../../../../../../../../../docs/engine/ui-layer.md).**
