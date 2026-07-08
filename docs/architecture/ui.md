# UI Layer

The `ui/` package is the JavaFX layer: the canvas, the node/edge views, inline
value editing, undo/redo, and save/load. It is the only package that owns
JavaFX-thread concerns, and it is the top of the dependency stack — it depends on
`graph/` (and below), never the reverse.

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
snapshot shape (`GraphCanvas.GraphSnapshot` / `ClipboardNode` / `ClipboardDataEdge`
/ `ClipboardFlowEdge`) built for copy/paste. The JSON conversion
(`toJson`/`fromJson`) is deliberately free of any JavaFX/GraphCanvas dependency so
it can be unit-tested headlessly; `save`/`load` are the thin wrappers that touch a
real canvas.

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
- **Backward compatibility:** unknown node types are skipped with a warning
  (rather than failing the load); missing `waypoints`/`sourcePort`/`targetPort`
  default sensibly so older saves still open, and a missing/unknown
  `executionPolicy` loads as `QUEUE`. When you change the format, keep this
  forgiving-read behavior and document the new fields.

## Per-node execution policy

Right-clicking a node opens a context menu (`NodeView.showContextMenu`) whose
**Execution Policy** submenu sets the node's `ExecutionPolicy` — what happens when
the node is triggered again mid-pass (see [graph-engine.md](graph-engine.md)). The
menu is rebuilt on each open so it reflects the node's current policy. `PARALLEL`
is shown disabled: it isn't implemented and falls back to `QUEUE`, so offering it
would mislead. The chosen policy round-trips through the save format above.

Only **execution entry points** (`BaseNode.isExecutionEntryPoint()` — nodes that
`execute()` themselves, like triggers, listeners, and the Discover-cameras button)
get the glyph and the menu; a constant, loader, or mid-cascade transform node shows
neither, and right-clicking it falls through to the canvas's add-node menu as before.

Each policy has a small glyph (`ExecutionPolicyIcons`, drawn from primitive JavaFX
shapes — no image assets): a ringed slash (Drop), a circular arrow (Restart), three
stacked lines (Queue), two upright bars (Parallel). `NodeView` renders the current
policy's glyph just left of the title (with a tooltip) and refreshes it when the
policy changes; the same glyphs appear beside the menu items. To add or restyle a
policy icon, edit `ExecutionPolicyIcons` — nothing else needs to change.

---

**When you change this, update…** this file whenever you change canvas
interactions, add a view type or a `Command`, change the `NodeContentProvider`
extension point, add a `ValueEditors` type, or alter the `GraphFileIO` JSON format
(also update the `GraphFileIO` Javadoc and keep backward-compat notes).
