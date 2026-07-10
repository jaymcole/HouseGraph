# `ui/` — the JavaFX layer

Full context: [`docs/architecture/ui.md`](../../../../../../../../docs/architecture/ui.md).
Start at the repo-root [`CLAUDE.md`](../../../../../../../../CLAUDE.md) if you haven't.

This is the only package that owns JavaFX-thread concerns. It sits at the top of
the dependency stack: it depends on `graph/` (and below), never the reverse.

## Layout

`GraphCanvas` is the hub and stays at the package root; everything else is split
by concern into sub-packages:

| Sub-package | Holds |
| --- | --- |
| `view/` | node/edge/port views (`NodeView`, `PortView`, `FlowPortView`, `EdgeView`, `FlowEdgeView`, `AbstractEdgeView`, `ConnectionView`, `EdgeAnchor`, `EdgeInteractionListener`), the `ExecutionPolicyIcons` glyphs, and the `NodeContentProvider` inline-UI extension point |
| `editor/` | inline value + secret editing (`ValueEditors`, `SecretsEditor`) |
| `command/` | undo/redo — the `Command` interface, `UndoManager`, and every `*Command` |
| `snapshot/` | the snapshot data model — `GraphSnapshot` and its `Clipboard*` records (a captured slice of the graph, shared by copy/paste and save/load) |
| `io/` | save/load (`GraphFileIO`) |
| `log/` | the standalone log viewer (`LogWindow`) rendering the `logging/` buffer sink, plus `LogLevelPreferences` (persists per-output levels via `AppPreferences`) |

Because these live in separate packages now, the cross-package API surface each
one exposes (e.g. `GraphCanvas`'s canvas-mutation methods, `UndoManager`'s
`execute`/`record`, `AbstractEdgeView`'s waypoint accessors) is `public`; keep
genuinely package-local helpers package-private. Prefer standalone files over
public nested types for anything shared across packages (that's why the snapshot
records live in `snapshot/` rather than inside `GraphCanvas`); a `private` nested
helper used in only one file is fine to leave nested. When you add a file, put it
in the sub-package that matches its concern (and mirror it under the matching test
package).

Hold these when editing here:

- **All view code runs on the FX Application Thread.** The engine runs passes on
  background threads and marshals its callbacks to the UI via its callback
  executor, which `GraphCanvas` sets to `Platform::runLater`. Never call into
  JavaFX from an engine thread, and never call blocking work on the FX thread
  (do it on a worker thread, then `Platform.runLater` the UI update — see
  `DiscordBotNode`'s connect flow).
- **Reversible canvas mutations are `Command`s.** Anything the user can undo goes
  through `UndoManager` as a `Command` (`execute()`/`undo()`), not an ad-hoc
  mutation. Use `record()` for gestures applied live (e.g. a drag) that become one
  undo step at the end.
- **Keep save/load logic headless.** `GraphFileIO`'s `toJson`/`fromJson` must stay
  free of JavaFX so they can be unit-tested; only `save`/`load` touch a canvas.
  When you change the JSON format, keep the forgiving-read/back-compat behavior and
  update the `GraphFileIO` Javadoc **and** the ui doc.
- **New manually-editable type?** Add one line to the `ValueEditors` static block —
  nothing else changes — and note it in the ui doc.

**When you change canvas interaction, views, commands, editors, or the save
format, update [`docs/architecture/ui.md`](../../../../../../../../docs/architecture/ui.md).**
