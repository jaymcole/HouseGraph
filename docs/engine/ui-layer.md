# UI layer

`ui/` is the JavaFX layer: the canvas, node and edge views, inline value editing,
undo/redo, and the auxiliary windows. It is the only package that owns FX-thread
concerns and the top of the dependency stack.

## Package layout

```
ui/
├── GraphCanvas.java   the hub (canvas host, drag controller, execution listener)
├── view/              NodeView, PortView, FlowPortView, EdgeView, FlowEdgeView,
│                      AbstractEdgeView, ConnectionView, EdgeAnchor,
│                      EdgeInteractionListener, ExecutionPolicyIcons
├── editor/            SecretsEditor
├── command/           Command, UndoManager, and every *Command
├── snapshot/          GraphSnapshot, ClipboardNode, ClipboardDataEdge, ClipboardFlowEdge
├── log/               LogWindow, LogLevelPreferences
├── plugin/            PluginWindow (the node-library manager)
└── io/                GraphFileIO
```

Java has no sub-package visibility, so pieces that call across these boundaries are
`public`: `GraphCanvas`'s canvas-mutation methods, `UndoManager`'s
`execute`/`record`/`undo`/`redo`, and `AbstractEdgeView`'s waypoint accessors are
an intentional API surface. Anything used within a single sub-package stays
package-private.

The `snapshot/` records are a plain data model — a captured slice of the graph —
shared by copy/paste, `command/` and `io/`, so they live on their own rather than
nested inside the canvas widget. The test tree mirrors this layout, which is how
`GraphFileIOTest` drives package-private `toJson`/`fromJson` headlessly.

**The node-facing extension points are not here.** `NodeContentProvider`,
`AutoStartable` and `ValueEditors` live in `sdk/` in the `housegraph-api` module,
because nodes live outside this repository where `app` is not on the classpath. The
consuming sites are still in this layer: `NodeView` dispatches
`NodeContentProvider`, `GraphCanvas.loadSnapshot` dispatches `AutoStartable`, and
`PortView` reads `ValueEditors`.

## Threading rule

**Everything in `ui/` runs on the FX Application Thread.** The engine runs on
background threads and dispatches callbacks through its callback executor, which
`GraphCanvas` sets to `Platform::runLater`, so engine work arrives already
marshalled. Never call into JavaFX from an engine thread, and never do blocking
work on the FX thread — move it to a worker and `Platform.runLater` the UI update.

## `GraphCanvas`

`GraphCanvas extends Pane` is an infinite, pannable, zoomable canvas hosting
`NodeView`s and the edge views between them. It owns a single `NodeGraph` and
implements three roles: `NodeView.DragController`, `GraphExecutionListener` (to
flash nodes and edges as they fire), and `EdgeInteractionListener`.

Interactions, with the class Javadoc as the authoritative list:

- Middle-drag on empty space pans; scroll zooms, anchored at the cursor.
- Left-drag on empty space rubber-band selects; right-click opens the canvas
  context menu — a ranked node search box, focused immediately and showing no
  results until you type, then the Add-Node menu below it for browsing by
  category folder (`NodeRegistry.discover()`, grouped by `categoryPath`). See
  "Node search box" below.
- Delete/Backspace removes the selection; `Ctrl/Cmd+C`/`V` copy and paste;
  `Ctrl/Cmd+Z` and `Shift+Z` undo and redo.
- Dragging between port circles makes a data edge; dragging between the triangular
  anchors at a node's top corners makes a flow edge.

While a data edge is dragged, every other port's anchor is coloured by how faithful
that connection would be. `GraphCanvas.connectionSafety` calls
`TypeConverters.classify`, and `PortView` fills the circle green (`SAFE`), yellow
(`CAUTIOUS`), orange (`RISKY`) or red (`INCOMPATIBLE`). A drag may only land on a
non-red port, mirroring `NodeGraph.attachEdge`. See
[type-system.md](type-system.md).

### Node search box

The context menu's first row is a `CustomMenuItem` wrapping a `TextField`, backed by
`NodeSearchIndex` (see [node-search.md](node-search.md)). `GraphCanvas` builds it once
and keeps that same `CustomMenuItem` instance for the life of the session —
`updateSearchResults` only ever replaces the rows *after* it via
`ContextMenu.getItems().setAll`. Recreating the search row itself on every keystroke
would tear the live `TextField` out of the scene graph and drop its focus mid-type.

Opening the menu (`setOnShowing`) clears the field and re-runs the search with an
empty query — deliberately zero result rows, not `NodeSearchIndex`'s browse-everything
reading of a blank query, since with a large or multi-library registry that would turn
every right-click into a scrollable wall of nodes. `setOnShown` then focuses the field
so typing works immediately. Each keystroke re-ranks the rows below it; Enter adds the
top-ranked result and closes the menu (a no-op on a still-blank query), Escape just
closes it. The categorised Add-Node menu stays underneath as the way to browse by
folder, and `reloadNodeTypes()` calls `NodeSearchIndex.invalidate()` alongside
rebuilding it.

## Views

| View | Renders |
| --- | --- |
| `NodeView` | a `BaseNode`: title bar with drag handle and corner flow anchors, left input column, right output column |
| `PortView` (`EdgeAnchor`) | one `NodeVariable`; drag its circle to make a data edge; inline editable field when the variable is manually editable and its type is in `ValueEditors` |
| `FlowPortView` (`EdgeAnchor`) | one `FlowPort` anchor |
| `EdgeView` / `FlowEdgeView` | the connecting curves, blue for data and green for flow |
| `AbstractEdgeView` (`ConnectionView`) | shared curve visuals: selection, traversal pulse, and manual **waypoint** re-routing — double-click adds a bendpoint; waypoints are purely visual and never touch the model |

Flow anchors come straight from `BaseNode.getFlowInputs()`/`getFlowOutputs()`, so a
branch node with several out-ports gets one anchor each automatically.

### Flow-edge wiring

A flow-in anchor accepts **several** incoming edges: two triggers can both be wired
into one `Start` port, and either firing triggers the node.
`GraphCanvas.createFlowEdge` adds alongside whatever already feeds the port rather
than replacing it. Data ports keep their single-source restriction, which is why
`CreateEdgeCommand` records a displaced edge for undo and `CreateFlowEdgeCommand`
has nothing to record.

The one gesture refused is wiring the *identical* pair of ports twice:
`isValidFlowConnection` calls `findFlowEdge` and rejects a pair already joined, so a
duplicate edge — which would change nothing but would inflate a
[flow join](concurrency.md)'s arrival count — cannot be dragged into existence.
`createFlowEdge` returns the existing view instead of a second one for the same
reason, covering the load, paste and node-rebuild paths that call it directly.

### Node visual states

`NodeView` layers unmanaged, mouse-transparent overlay rectangles over the node,
with an `INSIDE` stroke so they never shift or resize it:

- **Selected** — amber border.
- **Pulse** — a brief cyan flash when the node is triggered.
- **Processing** — animated orange marching ants while `process()` runs.
- **Misconfigured** — a persistent red border, a thin red border around each
  unsatisfied input `PortView`, and a tooltip naming them, shown whenever
  `BaseNode.isMisconfigured()`. `NodeView.refreshValidation()` recomputes it;
  `GraphCanvas` calls it when an edge is added or removed and `PortView` calls it
  when a manual value is committed.

  The port border is used rather than recolouring the anchor, which read as the
  drag "invalid target" state. Every port carries a transparent border of the same
  width by default, so toggling never reflows the node.

## Node context menu

Right-clicking a node opens `NodeView.showContextMenu`, rebuilt on each open so it
reflects current state.

| Submenu | Shown for | Sets |
| --- | --- | --- |
| Execution Policy | any node that participates in flow (`participatesInFlow()`) | `ExecutionPolicy` — all four values selectable |
| Concurrency limit | same | `maxConcurrency` |
| Process timeout | same | `timeoutMillis` |
| Required inputs | any node with inputs | per-input `required` flag, driving the misconfigured indicator |

These mutate the model directly rather than through the undo stack, and all
round-trip through the [save format](save-format.md). A node with none of them — a
constant, an input-less resource — shows no menu, and right-clicking falls through
to the canvas Add-Node menu.

Each policy has a glyph in `ExecutionPolicyIcons`, drawn from primitive JavaFX
shapes with no image assets: a ringed slash (Drop), a circular arrow (Restart),
three stacked lines (Queue), two upright bars (Parallel). `NodeView` renders the
current one left of the title with a tooltip. To add or restyle an icon, edit
`ExecutionPolicyIcons`; nothing else changes.

## Undo/redo

`UndoManager` keeps a linear history of `Command`s, each with `execute()` and
`undo()`. Executing a new command clears the redo stack.

- `execute(command)` runs a command for the first time and records it.
- `record(command)` records a command as *already applied*, for gestures applied
  live — a node drag updates position on every mouse-move for real-time feedback
  and becomes a single undo step when the gesture ends.

Current commands: `AddNodeCommand`, `RemoveNodesCommand`, `MoveNodesCommand`,
`CreateEdgeCommand`, `CreateFlowEdgeCommand`, `PasteCommand`,
`SetWaypointsCommand`.

**Model new reversible canvas mutations as a `Command`** rather than mutating the
canvas ad hoc, so they participate in undo.

## Auxiliary windows

Both are standalone, non-modal, unowned top-level stages with toggle-to-front
singleton `show()` methods — not modal dialogs like `SecretsEditor`.

**`log/LogWindow`** renders the shared `LogBufferSink`. On open it replays
`snapshot()`, the full retained history including everything captured while it was
closed, then follows live records through a listener marshalling each with
`Platform.runLater`; on close it detaches. Because the buffer keeps capturing,
reopening is lossless. It exposes a display filter, a per-sink level dropdown for
every registered output, and auto-scroll and clear. Rows are copyable: cell
selection is on, and a right-click menu or the platform copy shortcut copies the
focused cell or the selected rows, a row emitted as tab-separated columns.
Per-output level choices persist through `LogLevelPreferences`. The logging model
is in [logging.md](logging.md).

**`plugin/PluginWindow`** manages node libraries. Installing is long and
network-bound and the user should be able to watch the canvas and log window while
it runs, which a modal forbids. It is a deliberately thin shell; everything worth
testing lives in `plugin/`. See [plugin-runtime.md](plugin-runtime.md).

---

**When you change this, update…** this file whenever you change canvas
interactions, add a view type or a `Command`, change the context menu, or change
either auxiliary window. Save-format changes belong in
[save-format.md](save-format.md); extension-point changes also touch
[`../nodes/`](../nodes/).
