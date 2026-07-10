# UI Layer

The `ui/` package is the JavaFX layer: the canvas, the node/edge views, inline
value editing, undo/redo, and save/load. It is the only package that owns
JavaFX-thread concerns, and it is the top of the dependency stack — it depends on
`graph/` (and below), never the reverse.

## Package layout

`GraphCanvas` is the hub and lives at the `ui/` root. The rest of the layer is
grouped by concern into sub-packages:

```
ui/
├── GraphCanvas.java   the hub (canvas host, drag controller, execution listener)
├── view/              NodeView, PortView, FlowPortView, EdgeView, FlowEdgeView,
│                      AbstractEdgeView, ConnectionView, EdgeAnchor,
│                      EdgeInteractionListener, ExecutionPolicyIcons, NodeContentProvider
├── editor/            ValueEditors, SecretsEditor
├── command/           Command, UndoManager, and every *Command
├── snapshot/          GraphSnapshot, ClipboardNode, ClipboardDataEdge, ClipboardFlowEdge
├── log/               LogWindow (the standalone log viewer)
└── io/                GraphFileIO
```

Splitting the layer across packages means the pieces that call across those
boundaries are `public` (Java has no sub-package visibility): `GraphCanvas`'s
canvas-mutation methods, `UndoManager`'s `execute`/`record`/`undo`/`redo`, and
`AbstractEdgeView`'s waypoint accessors are all part of that intentional API
surface. Anything used only within a single sub-package stays package-private.
The `snapshot/` records are a plain data model — a captured slice of the graph —
that `GraphCanvas` (copy/paste), `command/` (paste), and `io/` (save/load) all
build on, so they live on their own rather than nested inside the canvas widget.
The test tree mirrors this layout (`GraphFileIOTest` lives under `ui/io/`, in the
same package as `GraphFileIO`, so it can drive its package-private
`toJson`/`fromJson` headlessly).

## `GraphCanvas` — the hub

`GraphCanvas extends Pane` is an infinite, pannable, zoomable canvas that hosts
`NodeView`s and the edge views between them. It owns a single `NodeGraph` and
implements three roles: `NodeView.DragController`, `GraphExecutionListener`
(to flash nodes/edges as they fire), and `EdgeInteractionListener`.

Interaction summary (see the class Javadoc for the authoritative list):

- Middle-drag on empty space = pan; scroll = zoom anchored at the cursor.
- Left-drag on empty space = rubber-band select; right-click = Add-Node menu
  (built from `NodeRegistry.discover()`, grouped by category folder).
- Delete/Backspace removes the selection; `Ctrl/Cmd+C` / `V` copy/paste;
  `Ctrl/Cmd+Z` / `Shift+Z` undo/redo.
- Drag between data ports' circles = data edge; drag between the triangular flow
  anchors at a node's top corners = flow edge.

**Threading rule:** everything here runs on the FX Application Thread. The engine
runs passes on background threads and dispatches callbacks to the UI through its
callback executor, which `GraphCanvas` sets to `Platform::runLater`. When engine
work needs to touch a view, it arrives already marshaled onto the FX thread — do
not call into JavaFX from an engine thread yourself.

## Views

| View | Renders |
| --- | --- |
| `NodeView` | a `BaseNode`: title bar (drag handle + flow anchors at the corners), left input column, right output column |
| `PortView` (`EdgeAnchor`) | one `NodeVariable`; drag its circle to make a data edge; inline editable field when the variable is manually editable and its type is in `ValueEditors` |
| `FlowPortView` (`EdgeAnchor`) | one `FlowPort` anchor |
| `EdgeView` / `FlowEdgeView` | the connecting curves (blue data / green flow); both extend `AbstractEdgeView` |
| `AbstractEdgeView` (`ConnectionView`) | shared curve visuals: selection, traversal pulse, and manual **waypoint** re-routing (double-click to add a bendpoint; waypoints are purely visual, never touching the model) |

Flow anchors are taken straight from `BaseNode.getFlowInputs()/getFlowOutputs()`,
so a branch node with several out-ports gets one anchor each automatically.

### Node visual states

A `NodeView` layers a few unmanaged, mouse-transparent overlay rectangles over the node
(unmanaged + `INSIDE` stroke, so they never shift or resize the node by a pixel):

- **Selected** — amber border (`highlightBorder`).
- **Pulse** — a brief cyan flash when the node is triggered, reverting to its resting state.
- **Processing** — animated orange "marching ants" while the node's `process()` is running.
- **Misconfigured** — a persistent red border (`validationBorder`) plus a thin red border around
  each unsatisfied input `PortView` (anchor + label/field) and a tooltip naming them, shown whenever
  `BaseNode.isMisconfigured()` (a [`required()`](nodes.md) input with no incoming edge and no
  manual value). `NodeView.refreshValidation()` recomputes it; `GraphCanvas` calls it when an
  edge to the node is added or removed, and `PortView` calls it when a manual value is committed.
  The port border is used rather than recoloring the anchor, which read like the drag
  "invalid target" state; every port carries a transparent border of the same width by default so
  toggling it never reflows the node. The selection/pulse border paints on top of the red node
  border, but the red port borders keep a misconfigured node's problem visible even while selected.

## Node inline UI: `NodeContentProvider`

A `BaseNode` subclass can implement `NodeContentProvider` to embed its own JavaFX
`Node` at the bottom of its `NodeView` — without knowing anything about `NodeView`
or `GraphCanvas`. `createNodeContent()` is called once when the view is built;
override `BaseNode.onExecuted()` to push fresh values into whatever you built.
`DiscordBotNode` is a full example (Connect/Disconnect buttons, status label);
the interface Javadoc has a minimal one.

## Inline value editing: `ValueEditors`

`ValueEditors` maps a type to a parse/format pair. A `NodeVariable` gets an inline
text field on its `PortView` only if it's `manuallyEditable` **and** its type is
registered here. Registered today: `Float`, `String`, `Integer`.

**To make a new type editable, add one line to the `ValueEditors` static block** —
nothing in `PortView` or elsewhere changes.

## Undo/redo: the `Command` pattern

`UndoManager` keeps a linear undo/redo history of `Command`s (each with
`execute()`/`undo()`). Executing a new command clears the redo stack.

- `execute(command)` runs a command for the first time and records it.
- `record(command)` records a command as *already applied* (does not call
  `execute()`), for gestures applied live — e.g. a node drag updates position on
  every mouse-move for real-time feedback and is wrapped into a single undo step
  only when the gesture ends.

Current commands: `AddNodeCommand`, `RemoveNodesCommand`, `MoveNodesCommand`,
`CreateEdgeCommand`, `CreateFlowEdgeCommand`, `PasteCommand`,
`SetWaypointsCommand`. **New reversible canvas mutations should be modeled as a
`Command`** rather than mutating the canvas ad hoc, so they participate in undo.

## Save / load: `GraphFileIO`

`GraphFileIO` serializes a canvas to JSON and back, reusing the same index-based
`snapshot` shape (`GraphSnapshot` / `ClipboardNode` / `ClipboardDataEdge` /
`ClipboardFlowEdge`, in the [`ui.snapshot`](#package-layout) package) built for
copy/paste. The JSON conversion (`toJson`/`fromJson`) is deliberately free of any
JavaFX/GraphCanvas dependency so it can be unit-tested headlessly; `save`/`load`
are the thin wrappers that touch a real canvas.

The toolbar (`App`) exposes three file actions. **Quick Save** writes straight to
the *current file* — the file most recently saved to or loaded from — with no
dialog; until one exists (fresh session that has never saved), it falls back to
the **Save As…** flow, which always prompts for a destination. **Load** opens a
file chooser. Saving or loading records the file as the current file and persists
its path (`AppPreferences.LAST_FILE`) so it reopens on the next launch — which
also seeds Quick Save's target for a reopened graph.

JSON shape:

```jsonc
{
  "nodes": [
    { "type": "<fully-qualified class name>",
      "x": 0.0, "y": 0.0,
      "executionPolicy": "QUEUE",  // DROP | RESTART | QUEUE | PARALLEL; absent = QUEUE
      "inputs":  [ /* positional; null where not persistent */ ],
      "outputs": [ /* positional; computed values written as null */ ],
      "state":   { /* optional saveState() map */ } }
  ],
  "dataEdges": [ { "sourceNode": 0, "sourceVariable": 0,
                   "targetNode": 1, "targetVariable": 0,
                   "waypoints": [ {"x":..,"y":..} ] } ],
  "flowEdges": [ { "sourceNode": 0, "sourcePort": 0,
                   "targetNode": 1, "targetPort": 0,
                   "waypoints": [ ... ] } ]
}
```

Key rules to preserve when editing this format:

- **Only persistent values are written** (`NodeVariable.isPersistentValue`);
  computed/secret/transient values are `null`, keeping stale data and secrets out
  of files. Null slots are still written so positional load stays aligned.
- **`state` is loaded before ports are touched**, so dynamic-port nodes rebuild
  their ports from state before values are applied.
- **Backward compatibility:** unknown node types load as an index-preserving
  placeholder with a warning (rather than failing the load); missing
  `waypoints`/`sourcePort`/`targetPort` default sensibly so older saves still open,
  and a missing/unknown `executionPolicy` loads as `QUEUE`. When you change the
  format, keep this forgiving-read behavior and document the new fields.
- **Skipped nodes hold their index slot.** A save-file node that can't be rebuilt
  (an unknown type) is kept in the loaded node list as a `ClipboardNode` with a
  `null` node, so it does **not** shift every later node's index. Without this, a
  single unknown node silently misdirected every edge after it onto the wrong
  nodes. `GraphCanvas.place` builds an index-aligned lookup list (a `null` slot per
  unbuilt node), places only the real nodes, and uses that list to resolve edges.
- **Edge reconnection is per-edge and self-contained** (`GraphCanvas.place`).
  Each saved edge is reconnected in isolation, and one whose endpoints no longer
  resolve — a node index past the loaded node count, a `null` placeholder slot, or a
  port index a node no longer has after its contract changed between saves — is
  dropped with a warning instead of aborting the loop. That isolation is
  deliberate: without it a single stale edge threw an `IndexOutOfBoundsException`
  that killed every remaining edge. Preserve it when touching the reconnect pass.

## Per-node execution policy

Right-clicking a node opens a context menu (`NodeView.showContextMenu`) whose
**Execution Policy** submenu sets the node's `ExecutionPolicy` — what happens when
the node is re-entered while work it started is still in flight (see
[graph-engine.md](graph-engine.md)). The menu is rebuilt on each open so it reflects the
node's current policy. All four policies (including `PARALLEL`) are selectable. The chosen
policy round-trips through the save format above.

The **Execution Policy** submenu and the policy glyph appear for **any node that participates
in flow** (`NodeView.participatesInFlow()` — has a flow port), not just execution entry points.
The policy is meaningful at both scopes: at an entry point it gates a whole re-triggered run, and
at a mid-cascade flow node it gates re-entry of that node's own `process()` across concurrent runs
(see [graph-engine.md](graph-engine.md)). The same flow-participating nodes also get **Concurrency
limit** and **Process timeout** submenus (per-node `maxConcurrency` / `timeoutMillis` — see
[nodes.md](nodes.md)). Any node with inputs also gets a **Required inputs** submenu: a checkbox per input toggling
its [`required`](nodes.md) flag (which drives the misconfigured indicator above). Like the
other submenus this mutates the model directly rather than through the undo stack, and the
per-input choice round-trips through the save format (`requiredInputs`). A node with none of
these (a constant, an input-less resource) shows no menu and right-clicking it falls through
to the canvas's add-node menu.

Each policy has a small glyph (`ExecutionPolicyIcons`, drawn from primitive JavaFX
shapes — no image assets): a ringed slash (Drop), a circular arrow (Restart), three
stacked lines (Queue), two upright bars (Parallel). `NodeView` renders the current
policy's glyph just left of the title (with a tooltip) and refreshes it when the
policy changes; the same glyphs appear beside the menu items. To add or restyle a
policy icon, edit `ExecutionPolicyIcons` — nothing else needs to change.

## The log window: `log/LogWindow`

The **Logs…** toolbar button opens `LogWindow`, the on-screen log viewer. Unlike the
modal `SecretsEditor`, it is a **standalone, non-modal top-level stage** not owned by the
main window, so it lives independently and can be closed and reopened at will. `show()` is
a toggle-to-front singleton.

It renders the shared `LogBufferSink` (from the `logging/` package): on open it replays
`snapshot()` — the full retained history, including everything captured while it was closed
— then follows live records through a listener that marshals each one with
`Platform.runLater`; on close it detaches the listener. Because the buffer keeps capturing
regardless, reopening is lossless. The window exposes a display-level filter, a per-sink
level dropdown for every registered output, and auto-scroll/clear. Per-output level choices
are persisted across launches by `LogLevelPreferences` (saved to `AppPreferences` by sink
name, reapplied by `App` after bootstrap). The logging model itself (levels, sinks,
bootstrap) lives in [logging.md](logging.md) — `LogWindow` is only its UI.

---

**When you change this, update…** this file whenever you change canvas
interactions, add a view type or a `Command`, change the `NodeContentProvider`
extension point, add a `ValueEditors` type, alter the `GraphFileIO` JSON format
(also update the `GraphFileIO` Javadoc and keep backward-compat notes), or change
the log window's behavior (also keep [logging.md](logging.md) in sync).
