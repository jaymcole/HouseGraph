# UI layer

`ui/` is the JavaFX layer: the canvas, node and edge views, inline value editing,
undo/redo, and the auxiliary windows. It is the only package that owns FX-thread
concerns and the top of the dependency stack.

The editor window this layer fills — what a window owns, what several of them share,
and what closing one tears down — is [windows.md](windows.md).

## Package layout

```
ui/
├── GraphCanvas.java   the hub (canvas host, drag controller, execution listener)
├── ModuleReferenceAction.java  the host's side of "add a node referencing a module"
├── PlacedGraph.java   what `place` drew: the node views and the group frames
├── view/              NodeView, GroupView, PortView, FlowPortView, EdgeView, FlowEdgeView,
│                      AbstractEdgeView, ConnectionView, EdgeAnchor,
│                      EdgeInteractionListener, ExecutionPolicyIcons
├── editor/            SecretsEditor
├── command/           Command, UndoManager, and every *Command
├── log/               LogWindow, LogLevelPreferences
├── menu/              MainMenuBar, MenuActions
├── plugin/            PluginWindow (the node-library manager)
├── module/            ModulePickerDialog (choose which module a node references)
├── export/            GraphComponents, GraphImageExport
├── widget/            TaskProgressBar (a Task-bound progress bar, reused across windows)
└── io/                GraphFileIO (thin save/load wrappers), RecentGraphs
```

Java has no sub-package visibility, so pieces that call across these boundaries are
`public`: `GraphCanvas`'s canvas-mutation methods, `UndoManager`'s
`execute`/`record`/`undo`/`redo`, and `AbstractEdgeView`'s waypoint accessors are
an intentional API surface. Anything used within a single sub-package stays
package-private.

The snapshot data model — `GraphSnapshot`, `ClipboardNode`, `ClipboardDataEdge`,
`ClipboardFlowEdge`, `NodeGroup` and `CameraState` — is a plain captured slice of the graph
shared by copy/paste, `command/`, `io/` and the headless `loader/` package, so it
does not live nested inside the canvas widget. It lives in `saveformat/`, outside
this layer entirely, alongside the JSON conversion that reads and writes it — see
[save-format.md](save-format.md) for why and `GraphFileIOTest` for the headless
`toJson`/`fromJson` coverage that split makes possible.

**The node-facing extension points are not here.** `NodeContentProvider`,
`AutoStartable`, `NodePresentation`, `NodeTimer` and `ValueEditors` live in `sdk/`
in the `housegraph-api` module, because nodes live outside this repository where
`app` is not on the classpath. The consuming sites are still in this layer:
`NodeView` dispatches `NodeContentProvider`, `GraphCanvas.loadSnapshot` dispatches
`AutoStartable`, and `PortView` reads `ValueEditors`.

**`NodeView` is also what makes a node "have a view".** `addNodeView` attaches a
`NodePresentation` to the node and `removeNodeView` clears it, so
`BaseNode.present(...)` reaches the controls exactly while they are on the canvas
and is a no-op the rest of the time. The sink runs its block inline when the caller
is already on the FX thread and `Platform.runLater`s it otherwise, which is how a
node's own clock updates a status label.

Two details are load-bearing. The view's constructor attaches it once more, *before*
`createNodeContent()`, because a node may present something while building its
controls and because `autoStartIfWasRunning()` follows moments later on a load.
And attaching belongs in `addNodeView` rather than only in the constructor because
undoing a delete re-adds the very same `NodeView` (`RemoveNodesCommand.undo`), whose
sink the removal cleared. Node-side guidance is in
[`../nodes/inline-ui.md`](../nodes/inline-ui.md#your-node-must-work-without-its-ui).

## Threading rule

**Everything in `ui/` runs on the FX Application Thread.** The engine runs on
background threads and dispatches callbacks through its callback executor, which
`GraphCanvas` sets to `Platform::runLater`, so engine work arrives already
marshalled. Never call into JavaFX from an engine thread, and never do blocking
work on the FX thread — move it to a worker and `Platform.runLater` the UI update.

## `GraphCanvas`

`GraphCanvas extends Pane` is an infinite, pannable, zoomable canvas hosting
`NodeView`s, the group frames behind them, and the edge views between them. It owns a
single `NodeGraph` and implements four roles: `NodeView.DragController`,
`GroupView.GroupController`, `GraphExecutionListener` (to flash nodes and edges as
they fire), and `EdgeInteractionListener`.

Interactions, with the class Javadoc as the authoritative list:

- Middle-drag on empty space pans; scroll zooms, anchored at the cursor.
- Left-drag on empty space rubber-band selects; right-click opens the canvas
  context menu — a ranked node search box, focused immediately and showing no
  results until you type, then the Add-Node menu below it for browsing by
  category folder (`NodeRegistry.discover()`, grouped by `categoryPath`), and an
  **Add Module…** row under it. See "Node search box" and "Modules" below.
- The rubber-band also catches individual edge waypoint handles (`AbstractEdgeView.
  waypointIndicesIn`), independently of whether the edge's curve itself is caught.
  Dragging any selected node then translates every selected waypoint by the same
  delta (`AbstractEdgeView.translateWaypoints`), so a manually-routed edge keeps its
  shape when the nodes around it move. `GraphCanvas` owns which waypoints are
  selected (`selectedWaypoints`, keyed by edge); `AbstractEdgeView` only exposes the
  hit-test, the highlight, and the translate.
- Delete/Backspace removes the selection; `Ctrl/Cmd+A` selects everything on the
  canvas, connections and group frames included; `Ctrl/Cmd+C`/`V` copy and paste;
  `Ctrl/Cmd+G` frames the selected nodes in a group; `Ctrl/Cmd+Z` and
  `Shift+Z` undo and redo. Each of these is also a `public` method, because the menu
  bar drives the same commands — see "Menu bar" below.
- A paste lands at the pointer: the copied nodes keep their relative layout and the
  top-left corner of the group goes under the cursor. `GraphCanvas` tracks the pointer
  with mouse filters rather than handlers, since a `NodeView` or `PortView` consumes
  the event before it could bubble back up. With the pointer off the canvas there is
  nothing to aim at, so the paste falls back to a fixed offset from the copied
  position; either way, repeated pastes without moving the pointer step further each
  time so they don't stack.
- `Ctrl/Cmd+F` opens the find bar over the top-right corner and rings every matching
  node in yellow; Escape closes it. See "Find bar" below.
- Dragging between port circles makes a data edge; dragging between the triangular
  anchors at a node's top corners makes a flow edge.
- A group frame's title bar drags it and everything inside it; its corner grips
  resize it. See "Groups" below.

While a data edge is dragged, every other port's anchor is coloured by how faithful
that connection would be. `GraphCanvas.connectionSafety` calls
`TypeConverters.classify`, and `PortView` fills the circle green (`SAFE`), yellow
(`CAUTIOUS`), orange (`RISKY`) or red (`INCOMPATIBLE`). A drag may only land on a
non-red port, mirroring `NodeGraph.attachEdge`. See
[type-system.md](type-system.md).

### Loading a snapshot

`GraphCanvas.place` puts a `GraphSnapshot` on the canvas — the shared path behind
paste (the factory duplicates the clipboard's nodes) and open-from-file (it unwraps
the nodes `saveformat.GraphFileIO` parsed). It is two layers:

- **`GraphLoader`**, in the headless `loader/` package, builds each node, registers
  it on the `NodeGraph`, and resolves every saved edge by index into the node's own
  variable and flow-port lists. No canvas, no scene, no view — see
  [architecture.md](architecture.md) for why it lives outside `ui/`, and
  [save-format.md](save-format.md) for the identity and isolation rules it keeps.
- **`place` itself**, which draws the result: a `NodeView` per node, then
  `forceLayout()`, then a view per edge the loader wired, carrying the routing
  waypoints — the one part of a snapshot the engine has no place for.

Two orderings are load-bearing. The loader reports each node through a
`GraphLoadListener` *before* registering it, so the `NodeView` — and with it
`createNodeContent()` — exists by the time `NodeGraph.addNode` fires `onActivated()`.
And `forceLayout()` runs after the nodes and before any edge view, because an edge
computes its curve from live port positions in its constructor.

Because the loader has already registered the edges, `createEdge` and
`createFlowEdge` are split: each wires the model and then calls the private
`attachEdgeView`/`attachFlowEdgeView`, which is what `place` uses on its own.
It matches ports to their views by identity rather than re-resolving the saved
index, so a `NodeView`'s port order cannot rewire a loaded graph.

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

### Find bar

`Ctrl/Cmd+F` shows a `TextField` and a match count in the canvas's top-right corner,
and every node whose text contains the query is ringed in yellow. The rule for what
matches is `search/GraphSearch`, which is headless and holds the interesting
decisions — including that a secret and a computed value are never searchable. See
[find-in-graph.md](find-in-graph.md); only the three FX-side facts are here.

The bar is a child of this `Pane` rather than of the content `Group`, so it floats at a
fixed corner instead of panning and zooming with the graph. `layoutChildren` is the only
place its position is set: `Region`'s layout pass sizes managed children without moving
them, and the position depends on the bar's own width, which changes with the count text.

`addNodeView`/`removeNodeView` re-judge just the node that changed while the bar is open,
rather than re-running the query over the canvas — otherwise opening a large graph with a
find in progress would be quadratic. The count is then recomputed from the marks already
on the views.

Closing drops every highlight but keeps the query text, so the shortcut both repeats a
search and starts a new one (it reopens with the text selected).

## Groups

A **group** is a labelled rectangle drawn behind the graph so a large canvas can be
read at a glance. It is a frame, not a node: it has no ports, never runs, and a graph
loaded without its frames behaves identically. `saveformat/NodeGroup` is the whole of
one — a title, a rectangle and a colour — and `view/GroupView` draws it.

**Membership is geometry, and nothing else.** A frame holds no list of what it
contains. It *commands* a node, or another frame, exactly while that thing's
rectangle lies wholly inside its own, recomputed at the moment an action needs the
answer. So dragging a node out of a frame is all it takes to leave the frame, there
is nothing to keep in step with the canvas, and a frame needs no id — nothing refers
to one. Wholly inside rather than overlapping, because taking along a node the user
can see is half out would read as a bug.

**Nesting is by size, and the relation is one-way.** `NodeGroup.commands(NodeGroup)`
is "strictly larger, and containing": the outer frame carries the inner one, never
the reverse. Two frames on the same rectangle therefore command each other in
neither direction, which is what stops "apply this to everything I contain" running
in a circle; two frames that merely overlap likewise, with a node in the overlap
commanded by both. Nesting needs no recursion at any depth — a node inside an inner
frame is inside the outer one by the same test, so one pass over the frames being
dragged already reaches everything.

**Paint order is the same rule.** `restackGroups()` sorts every frame by descending
area and moves the lot to the front of the content group's child list. Child order
*is* paint order in a JavaFX `Group`, so that one sort is the whole of "frames render
behind the graph, and a smaller frame renders on top of a larger one". Sorting over
the whole set rather than only over nested pairs is what makes two merely-overlapping
frames stack predictably too. It runs whenever the set of frames or any frame's size
changes — including from `SetGroupCommand`, so undoing a resize restacks as well.

### What an action does to a frame's contents

| Action | Reaches |
| --- | --- |
| Drag the title bar | the frame, every frame it commands, every node any of those commands, and every routing waypoint inside one — recorded as one `CompositeCommand` |
| Drag a corner grip | the frame's rectangle only. That *is* the point: growing a frame over a node is how the node joins it |
| Copy | the frame plus everything it commands, whether or not those nodes were selected — copying a labelled region has to copy the region |
| Delete | **the frame only.** A frame is a large target laid over real work, and cascading a delete through it would put an automation one mis-aimed keystroke from gone |
| Rubber band | the frame, but only when the band encloses it **whole** — a node is caught on a mere intersection, but a frame is a background region, and catching it from any band drawn inside it would mean the next drag moved everything else in it too |

Dragging nodes never moves a frame: containment runs one way, from the frame to what
is inside it.

**The frame's body takes no mouse input at all** — only the title bar and the four
corner grips do. A large background region that swallowed clicks would make the
canvas inside it unusable: no rubber band, no click-through to what is behind. That
is the same division `NodeView` makes, where the title bar drags and the body does
not. The title bar is inset by one grip width so the top-left grip stays reachable
beside it, and capped so it never grows over the top-right one.

**A frame hands keyboard focus back when it is done with it** (`GroupController.
focusCanvas()`). The inline title editor is hidden *while it still holds focus* —
Enter commits and closes it in one step — and JavaFX does not move focus off a node
just because it became invisible. Without this the hidden field stays the scene's
focus owner and goes on swallowing every shortcut, so renaming a frame would quietly
cost the user Ctrl/Cmd+Z, Delete, copy and paste. It runs on every exit from the
editor, Escape and a no-op commit included, because only some of them reach
`onGroupFrameEdited`. A grip press does the same, for the reason a `NodeView` drag
focuses the canvas: a gesture that consumes its own press leaves focus wherever it
was.

Frames are created from the canvas context menu's last row and from **Edit ▸ Group
Selection** (`Ctrl/Cmd+G`). With a selection the new frame is fitted around it, with
extra headroom at the top for the title bar; with nothing selected it is a default-
sized empty frame at the click point. The context-menu row relabels itself in
`setOnShowing` to say which it will do.

`GraphCanvas` keeps `groupViews` in **creation** order, not paint order, which is
what keeps a re-save of an unchanged canvas byte-identical even after a resize has
reordered the painting.

## Modules

Modules are the one thing the Add-Node menu structurally cannot offer. That menu is
built from `NodeRegistry.discover()`, which is keyed by **class** — and every module
is the same `ModuleNode` class pointed at a different id — so they need a listing of
their own. Two surfaces provide it, and neither decides anything:

- **Add Module…**, the context-menu row under the Add-Node menu. `GraphCanvas`
  contributes the row and the placement; the answer comes from an injected
  `ModuleReferenceAction`, which `GraphWindow` implements. The canvas closes over the drop
  point when the row is clicked rather than reading it when the node comes back,
  because the answer may arrive after a worker has been to the filesystem.
- **File ▸ Publish as Module…**, a `MenuActions` command, for the same reason every
  other file command is one.

Both run their filesystem work on a worker and come back through `Platform.runLater`:
listing modules refreshes `ModuleLibrary`'s index and derives each module's interface
by building its nodes, and publishing writes a file. `ui/module/ModulePickerDialog`
renders the rows `modules/ModuleChoices` produced; `modules/ModulePublisher` decides
what may be published and what the user is told. Everything worth testing is in
`modules/`, which is headless — see [architecture.md](architecture.md).

`GraphCanvas` also takes a `ModuleDirectory`, which it uses for one thing:
`loadSnapshot` resolves every loaded `ModuleNode` through `modules/ModuleBinding`
before resuming anything. A module node that is never bound reports itself
misconfigured and refuses to run, so without that pass a saved graph containing a
module would open dead. It runs **after** `place` because binding rebuilds a node's
ports when its module's interface has changed, and a rebuild re-attaches that node's
edges by name — which needs them to exist. It runs **before** `resumeRunningNodes`
because a resumed node may pull a value straight through a module. It deliberately
does **not** run inside `place`, which paste and redo also use: a rebuild replaces the
`NodeView` a `Command` is holding.

## Views

| View | Renders |
| --- | --- |
| `NodeView` | a `BaseNode`: title bar with drag handle and corner flow anchors, left input column, right output column |
| `GroupView` | a `NodeGroup`: a translucent labelled rectangle behind the graph. Mouse-transparent body, a draggable/editable title bar at the top-left, four corner resize grips |
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
reason, covering the node-rebuild path that calls it directly; `GraphLoader` applies
the same rule to the model when it loads a pair saved twice.

### Node visual states

`NodeView` layers unmanaged, mouse-transparent overlay rectangles over the node,
with an `INSIDE` stroke so they never shift or resize it:

- **Selected** — amber border.
- **Find hit** — a yellow border while the find bar's query matches this node. Painted
  over the selection border and wider than it, so a node that is both reads as a hit:
  that is the state the user is scanning for. `GraphCanvas` owns which nodes match.
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

## Image export

`export/` writes a PNG of each **connected component** of the graph — the distinct
automations one save file can hold. `GraphComponents` does the splitting and
`GraphImageExport` does the drawing; the **Export Images…** command is the only
caller.

`GraphComponents.connectedComponents` treats data and flow edges alike, and ignores
direction. That is the one place in this codebase where the two kinds are folded
together, and it is not a violation of [decision 0001](../decisions/0001-separate-data-and-flow-edges.md):
the question here is "which nodes belong in the same picture", which is about
reachability on the canvas rather than about how values or execution travel. It is
free of JavaFX and unit-tested headlessly. Components come back in `getNodes()`
insertion order, so a component's index — and therefore its filename — is stable.

### Isolating a component

`GraphCanvas.withComponentIsolated(component, renderer)` hides every node and edge
view outside `component`, clears the selection, resets pan/zoom to 1:1, runs the
renderer against the content group, and restores all three in a `finally`.

Group frames are hidden by a rule of their own: a frame is drawn only into the
pictures of the components it actually holds something of. A frame is not a node and
so belongs to no component, and one laid across the canvas would otherwise stretch
every component's crop rectangle out to cover it.

Hiding rather than cropping is the point. Nothing constrains two disjoint components
to occupy separate regions — a user may lay one out straight through the middle of
another — so a crop to a bounding box would pull foreign nodes into the picture.
Hiding also shrinks `Group.getLayoutBounds()` to what remains, which is where the
renderer gets its crop rectangle. Clearing the selection keeps `NodeView`'s amber
border out of the image — and the find-in-graph highlights go with it, for the same
reason — and the 1:1 reset makes the content group's local coordinates coincide with
its parent's, which is what lets the viewport be derived from `getLayoutBounds()`.

### Why the render is tiled

A single whole-canvas `snapshot` fails above the graphics pipeline's maximum texture
size — around 8192px square — and fails *inside* the render, as
`NullPointerException: Cannot invoke "com.sun.prism.Image.getWidth()"`, not as
anything a caller can interpret. A tidily laid-out graph crosses that at roughly 350
nodes and a sparse one far sooner, since empty canvas between clusters costs full
pixels. So the render is always tiled rather than attempted whole with a fallback.

Each tile is a `snapshot` of a 2048px-square window; `SnapshotParameters.setViewport`
takes its rectangle in **post-transform** pixels, which is what makes the windows line
up exactly. Tiles are copied into one `BufferedImage` through a reused
`WritableImage` and `int[]`, and written with `ImageIO` — `javafx.swing` is
deliberately *not* a dependency, since `PixelReader.getPixels` reaches a
`BufferedImage` without `SwingFXUtils`.

Two other snapshot behaviours the code depends on: the default fill is opaque
**white**, not transparent, so `setFill` is set to the canvas background explicitly;
and rendering is 1:1, because supersampling multiplies the buffer by the square of
the factor. `MAX_PIXELS` is a backstop for a pathological layout — one node dragged
tens of thousands of pixels from its component — and reduces the scale rather than
letting the export run out of memory.

## Menu bar

A window's chrome is `menu/MainMenuBar` over a short `ToolBar`, both in
`GraphWindow`'s `BorderPane` top. The menus are File, Edit, View, Run, Tools and Help,
and each window builds its own.

Commands come from two places, and that split is why the menu bar is its own class
rather than more of `GraphWindow`:

- **Canvas commands** — undo, redo, copy, paste, delete, select-all, find, the four
  zoom commands, Group Selection — are called straight on the `GraphCanvas` the menu
  bar is constructed with. This is what the `public` methods listed under
  `GraphCanvas` above are for.
- **Window commands** — anything needing the stage, the preferences store, the
  plugin catalog or the module library — go through `menu/MenuActions`, which
  `GraphWindow` implements. **File ▸ Publish as Module…** is one of these. The menu
  bar therefore depends on a named set of commands rather than on the window, which
  would be a cycle since the window constructs it.

The File menu is also where windows are managed: **New Graph** and **Open…** act on
this window, **New Window** (`Ctrl/Cmd+Shift+N`) and **Open in New Window…**
(`Ctrl/Cmd+Shift+O`) open another, **Close Window** (`Ctrl/Cmd+W`) closes this one and
**Exit** quits the lot. Shift is the modifier because that is what the platforms
already use for "same command, new window". **Open Recent** opens in the current
window.

**Accelerators duplicate the canvas's own key handling deliberately.** The canvas
handles and *consumes* Delete and the `Ctrl/Cmd` editing shortcuts, and JavaFX
processes a scene's accelerators only after an event has bubbled unconsumed — so
with canvas focus its handler wins and the identical accelerator never fires. The
accelerator is what makes those keys work when focus is elsewhere (a toolbar
button), and what puts the shortcut hint beside each menu item, which is where most
people discover it. A focused text field consumes the same keys, so typing in a
node's inline editor never reaches the menu.

**Enablement is recomputed in `setOnShowing`.** Undo depth, selection and clipboard
are plain state on the canvas with nothing to observe, and a closed menu cannot be
looked at, so recomputing as each menu opens is both sufficient and cheap. The same
hook renames **Save** to **Save…** while no file has been chosen, since until then it
prompts.

The toolbar under it is a shortcut strip: New, Open, Save, Undo, Redo, Zoom to Fit.
Every one of them is also a menu item — nothing lives in the toolbar alone, which is
what keeps it short. The missing-libraries notice is the one exception, because it is
a status indicator rather than a command and has no place in a menu.

## Recent graphs

**File ▸ Open Recent** is a `Menu` in `MainMenuBar`, rebuilt on every open from
`io/RecentGraphs` — so it reflects whatever has been saved or loaded since, with no
refresh plumbing. Choosing an entry takes the same `openGraph(..., interactive)` path
as **File ▸ Open**, which is what makes a missing node library prompt rather than
leave a quiet toolbar notice.

An entry whose file is gone is shown disabled and marked, because the list is not
pruned — see [storage.md](storage.md) for why, and for the on-disk shape. The menu
always holds at least one item, a disabled placeholder before anything has been
opened, since a `Menu` with no items silently refuses to open its popup.

`RecentGraphs` itself is free of JavaFX, like the rest of `io/`, and is unit-tested
headlessly against a temp preferences file.

## Undo/redo

`UndoManager` keeps a linear history of `Command`s, each with `execute()` and
`undo()`. Executing a new command clears the redo stack.

- `execute(command)` runs a command for the first time and records it.
- `record(command)` records a command as *already applied*, for gestures applied
  live — a node drag updates position on every mouse-move for real-time feedback
  and becomes a single undo step when the gesture ends.

Current commands: `AddNodeCommand`, `RemoveNodesCommand`, `MoveNodesCommand`,
`CreateEdgeCommand`, `CreateFlowEdgeCommand`, `PasteCommand`,
`SetWaypointsCommand`, `AddGroupCommand`, `RemoveGroupsCommand`, `SetGroupCommand`,
`CompositeCommand` (bundles several already-applied commands
into one undo step — e.g. a node drag that also carries selected waypoints along
records a `MoveNodesCommand` plus a `SetWaypointsCommand` per moved edge, wrapped
together so one undo reverts both).

`SetGroupCommand` covers a frame's move, resize, rename **and** recolour with one
class, because `NodeGroup` is a frame's whole state as a single immutable value:
"what it was" and "what it is now" are two of them and there is nothing else to
capture.

**Model new reversible canvas mutations as a `Command`** rather than mutating the
canvas ad hoc, so they participate in undo.

## Auxiliary windows

Both are standalone, non-modal, unowned top-level stages with toggle-to-front
singleton `show()` methods — not modal dialogs like `SecretsEditor`. One of each
serves the whole app: opening **Logs** from a second editor window raises the window
already showing, and both act on state `App` owns rather than on any one canvas.

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
testing lives in `plugin/`. See [plugin-runtime.md](plugin-runtime.md). A
downloading row — an add-from-URL asset or an update in progress — shows a
`widget/TaskProgressBar` in its own Status cell in place of the row's text, rather
than one shared bar for the whole window; each row's cell watches that row's own
`activeInstall` property and switches itself, so nothing elsewhere has to remember
to refresh the table when a download starts or finishes.

---

**When you change this, update…** this file whenever you change canvas
interactions, add a view type or a `Command`, change the context menu, change the
menus or the toolbar, change a node's visual states, change what a group frame
commands or how frames stack, change either auxiliary window, change what image export
draws, or change when a node's `NodePresentation` is installed or cleared. How editor
windows are opened, closed and torn down belongs in [windows.md](windows.md);
save-format changes in [save-format.md](save-format.md); extension-point changes also
touch
[`../nodes/`](../nodes/).
