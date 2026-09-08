# Getting started

## Requirements

A JDK that Gradle can use. The build targets **Java 21** with **JavaFX 21**, both
provisioned by Gradle, so you do not need either installed globally.

## Run it

```bash
./gradlew run
```

That launches the desktop app. To run the tests:

```bash
./gradlew test
```

## Build a standalone jar

To run without an IDE or Gradle, build a self-contained jar. It bundles JavaFX,
`housegraph-api` and every other dependency:

```bash
./gradlew :app:shadowJar
```

```bash
java -jar app/build/libs/app-<version>.jar
```

> **The jar bundles JavaFX's native libraries for the platform it was built on.**
> A jar built on Linux will not start on macOS, and one built on an Intel Mac will
> not run natively on Apple Silicon. Build on the machine that will run it.

Node libraries are unaffected — they are fetched and loaded at runtime, not bundled
into this jar.

## Your first graph

Nodes are wired on an infinite canvas. Two kinds of connection run between them,
and the difference is the central idea:

- **Data edges** carry a typed value from one node's output to another's input.
  They are *pulled*: a node resolves its inputs when it needs them.
- **Flow edges** carry no value. They define execution order, and are *pushed*
  when a trigger fires.

To build something:

1. **Right-click empty canvas** to open the Add-Node menu, grouped by category.
2. **Drag between the circles** on two ports to make a data edge. While you drag,
   every candidate port is coloured by how faithful the connection would be —
   green is exact or lossless, yellow loses precision, orange is drastic, red
   cannot connect at all.
3. **Drag between the triangular anchors** at a node's top corners to make a flow
   edge.
4. **Type values** directly into editable input fields.
5. Add a trigger node and press its button.

While a graph runs, nodes flash cyan as they fire and show orange marching ants
while working. A node with a red border is misconfigured — hover it for a tooltip
naming the inputs that need a value.

### Finding a node

`Ctrl/Cmd+F` opens a find box in the canvas's top-right corner. Every node matching
what you type is ringed in yellow, and the box counts them — so a match scrolled off
the edge of the window still tells you it is there. Escape closes it.

It searches a node's name, its port names, and **the text you typed into it**, which
is usually what tells two nodes of the same kind apart. Values you cannot see are not
searched: a secret is never matched, and neither is a value the graph computed while
running.

### Grouping a region

Once a graph is big enough that you have to hunt for things, wrap parts of it in a
**group**: a labelled, coloured rectangle drawn behind the nodes.

Select the nodes you want and press `Ctrl/Cmd+G` (or right-click and choose **Group
Selection**) and a frame appears around them. Right-clicking empty canvas and
choosing **Add Group** drops an empty one you can then drag nodes into.

| To | Do |
| --- | --- |
| Move the group and everything in it | drag its title bar |
| Resize it | drag any corner grip |
| Rename it | double-click the title, or right-click it ▸ **Rename…** |
| Recolour it | right-click the title ▸ **Colour** |
| Delete the frame | click its title bar, then Delete — **the nodes inside stay put** |

**A group has no memory of what is in it.** Whatever sits entirely inside the
rectangle is in the group, worked out fresh each time you move it. Drag a node out
and it is out; grow the frame over a node and it is in. Nothing to maintain.

Frames can be nested: draw a smaller one inside a larger one and the smaller always
draws on top, while dragging the larger one carries the smaller one and its contents
along. The reverse never happens — moving an inner group never disturbs the group
around it.

Groups are saved with the graph and appear in exported images. They are decoration:
they never change what a graph does or how fast it runs.

### Canvas controls

| Action | Gesture |
| --- | --- |
| Pan | middle-drag empty space |
| Zoom | scroll (anchored at the cursor) |
| Select | left-drag empty space for a rubber band |
| Select everything | `Ctrl/Cmd+A` |
| Find nodes | `Ctrl/Cmd+F`, then type — every match is ringed in yellow |
| Delete selection | Delete or Backspace |
| Copy / paste | `Ctrl/Cmd+C` / `Ctrl/Cmd+V` (paste lands at the cursor) |
| Group the selection | `Ctrl/Cmd+G` — see "Grouping a region" above |
| Undo / redo | `Ctrl/Cmd+Z` / `Ctrl/Cmd+Shift+Z` |
| Bend an edge | double-click it to add a waypoint |
| Node options | right-click a node |
| Group options | right-click a group's title bar |

The full list, including the file and zoom shortcuts, is under **Help ▸ Keyboard
Shortcuts**.

### The menus

Commands live in the menu bar; the strip under it repeats the handful used most.

| Menu | Holds |
| --- | --- |
| **File** | New Graph, New Window, Open, Open in New Window, Open Recent, Save, Save As, Export Images, Close Window, Exit |
| **Edit** | Undo, Redo, Copy, Paste, Delete, Group Selection, Select All, Find in Graph |
| **View** | Zoom In / Out, Actual Size, Zoom to Fit |
| **Run** | Watch Speed |
| **Tools** | Secrets, Node Libraries, Logs, Open Data Folder |
| **Help** | Documentation, Keyboard Shortcuts, About |

## Watching a graph run

Nodes flash as they fire, but a real graph finishes in a blink — the whole cascade
lights up and clears before you can see which node went first. **Run ▸ Watch Speed**
fixes that: pick a pace (0.25s, 0.5s, 1s, 2s) and the engine pauses for that long
before each node runs, so a trigger visibly walks its way downstream. Set it back to
**Off** to run at full speed.

Watch what it shows you about how the engine actually works: a node that fans out to
two branches lights *both* at once and they descend side by side, because branches
run concurrently rather than one after the other. A join node sits dark until every
branch feeding it has arrived.

Two things to know before leaning on it:

- **It slows the graph, so timing-dependent graphs behave differently.** A trigger
  firing faster than the delay will have firings dropped or coalesced, depending on
  each node's re-entrancy policy. A for-each loop pays the delay on every iteration,
  so a hundred items at 0.5s is fifty seconds.
- **It is not saved.** The setting lasts for the session and belongs to the app, not
  the graph, so it can never follow a graph onto a machine
  [running unattended](server-operations.md).

## Saving

**File ▸ Save** (`Ctrl/Cmd+S`) writes to the current file with no dialog. Until
there is one it falls back to **Save As…**, and the menu shows it as **Save…** to
say so. The window title carries the open file's name, and the last file you saved
or loaded reopens on the next launch.

**File ▸ Open Recent** lists the last ten graphs you saved or opened, newest first,
so reopening one is a click rather than a trip through the file dialog. A graph on a
drive that is not plugged in stays on the list, greyed out until it is back. **Clear
Recent Graphs**, at the bottom of the submenu, empties it.

**File ▸ New Graph** empties the canvas and asks first, since anything unsaved is
lost. It does not change which file reopens on the next launch.

Graphs are saved as JSON. Computed values and secrets are never written — computed
outputs are recalculated on load, and a node stores only a *reference* to a secret.

If a graph uses a node library you do not have installed, **it still opens**. Those
nodes are preserved exactly as they were, shown as placeholders, and offered for
install. Nothing is lost, including if you save again.

## Working in several windows

**File ▸ Open in New Window…** (`Ctrl/Cmd+Shift+O`) opens a graph beside the one you
are editing instead of replacing it, so you can read one graph while building another
or watch two automations run at once. **File ▸ New Window** (`Ctrl/Cmd+Shift+N`) opens
an empty one. **File ▸ Open…** and **Open Recent** still open in the window you are
in.

Each window is its own document: its own file, its own undo history, its own
clipboard, its own [Watch Speed](#watching-a-graph-run). **Save** writes the file of
the window you are in, and the title bar tells you which that is. Copy and paste stay
inside one window — there is no copying nodes from one window into another.

Asking to open a file that is already open somewhere brings that window forward
rather than opening a second copy — two windows editing one file would overwrite each
other on save.

**File ▸ Close Window** (`Ctrl/Cmd+W`) closes one window, shutting down whatever its
graph had running — timers, connections, cameras — and leaves the others alone.
Closing the last window quits, as does **File ▸ Exit**.

Named resources are the one thing windows share: a
[long-lived resource](../nodes/long-lived-resources.md) registered by a node in one
window can be listened to from another, because those are found by name across the
whole app rather than wired on a canvas.

## Exporting images

**File ▸ Export Images…** asks for a folder and writes a PNG of the canvas into it,
at the same 1:1 scale you see on screen — no need to zoom out, and no screenshot
cropping.

One canvas often holds several unrelated automations side by side, so **each one is
exported as its own image**, containing only its own nodes. Two automations laid out
across each other still come out cleanly separated.

Images are named after the open graph file: `lights.json` exports as `lights.png`,
or as `lights-1.png`, `lights-2.png` … when the file holds more than one automation,
numbered top-to-bottom then left-to-right by where they sit on the canvas. An unwired
node counts as an automation of its own.

Your selection, pan and zoom are untouched — the picture never shows selection
highlights, whatever was selected when you pressed the button.

## Next

| Want to… | See |
| --- | --- |
| Add cameras, Discord, a web server | [node-libraries.md](node-libraries.md) |
| Store a token or password | [secrets.md](secrets.md) |
| Run graphs 24/7 on another machine | [server-setup.md](server-setup.md) |
| Write your own node | [`../nodes/`](../nodes/) |

## Configuration

App data lives in an OS-appropriate directory:

- **Windows:** `%APPDATA%\HouseGraph`
- **macOS:** `~/Library/Application Support/HouseGraph`
- **Linux:** `$XDG_DATA_HOME/HouseGraph`, else `~/.local/share/HouseGraph`

Override the root with the `HOUSEGRAPH_HOME` environment variable, the
`housegraph.home` system property, or `--home` on any CLI command.

A gitignored `.env` file seeds the Secret Loader node's dropdown; see
`.env.example`.

---

**When you change this, update…** this file whenever the build or run commands
change, the canvas gestures, the menus or the toolbar change, or the data directory
locations change.
