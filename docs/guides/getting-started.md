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

### Canvas controls

| Action | Gesture |
| --- | --- |
| Pan | middle-drag empty space |
| Zoom | scroll (anchored at the cursor) |
| Select | left-drag empty space for a rubber band |
| Delete selection | Delete or Backspace |
| Copy / paste | `Ctrl/Cmd+C` / `Ctrl/Cmd+V` |
| Undo / redo | `Ctrl/Cmd+Z` / `Ctrl/Cmd+Shift+Z` |
| Bend an edge | double-click it to add a waypoint |
| Node options | right-click a node |

## Saving

**Quick Save** writes to the current file with no dialog. Until there is one it
falls back to **Save As…**. The last file you saved or loaded reopens on the next
launch.

Graphs are saved as JSON. Computed values and secrets are never written — computed
outputs are recalculated on load, and a node stores only a *reference* to a secret.

If a graph uses a node library you do not have installed, **it still opens**. Those
nodes are preserved exactly as they were, shown as placeholders, and offered for
install. Nothing is lost, including if you save again.

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
change, the canvas gestures change, or the data directory locations change.
