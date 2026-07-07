# HouseGraph

A JavaFX desktop app for building **home-automation graphs**, with a focus on
computer-vision triggers. Wire nodes together on an infinite canvas — constants,
math, converters, control-flow branches, camera-motion sensors, Discord bots, an
Arduino "squirrel alarm" sign — into graphs that react to events. Graphs are
saved as JSON and reopened between sessions.

Two kinds of connection run between nodes, and keeping them distinct is the core
design idea:

- **Data edges** carry a typed value from one node's output to another's input
  (pulled on demand).
- **Flow edges** carry no value — they define execution order (pushed when a
  trigger fires).

## Features

- Infinite, pannable, zoomable node canvas with rubber-band select, copy/paste,
  undo/redo, and manual edge routing.
- A node library discovered automatically from the classpath — add a class, it
  shows up in the menu.
- Concurrent, thread-safe execution engine with branch fan-out and data-cycle
  detection.
- Integrations: Discord bots (via JDA), ONVIF/Reolink IP-camera discovery and
  motion detection, and an Arduino UNO R4 WiFi LED-matrix device.
- Encrypted secret store (AES-256-GCM) so tokens and passwords never touch save
  files.

## Build & run

Requires nothing preinstalled beyond a JDK that Gradle can use; the build targets
**Java 21** with **JavaFX 21**.

```bash
./gradlew run     # launch the app
./gradlew test    # run the JUnit 5 test suite
```

The `main` you run is `io.github.jaymcole.housegraph.Launcher`.

## Documentation

Start with **[`CLAUDE.md`](CLAUDE.md)** — the architecture map, the standards the
code holds itself to, and (importantly) the rule that **changes must keep the docs
in sync**. Subsystem deep-dives live in
**[`docs/architecture/`](docs/architecture/)**:

| Doc | Covers |
| --- | --- |
| [overview.md](docs/architecture/overview.md) | layering, dependency direction, graph lifecycle |
| [graph-engine.md](docs/architecture/graph-engine.md) | execution model, threading, cycle detection |
| [nodes.md](docs/architecture/nodes.md) | the node model and how to add a node |
| [ui.md](docs/architecture/ui.md) | canvas, views, undo, save/load |
| [resources.md](docs/architecture/resources.md) | named resources & event pub/sub |
| [storage-and-secrets.md](docs/architecture/storage-and-secrets.md) | on-disk layout, encrypted secrets |
| [integrations.md](docs/architecture/integrations.md) | Discord, cameras, the Arduino device |
| [testing.md](docs/architecture/testing.md) | test conventions |

High-traffic packages (`graph/`, `graph/nodes/`, `ui/`) also carry their own
`CLAUDE.md` with local rules.

## Configuration

- `.env` (gitignored; see [`.env.example`](.env.example)) seeds the Secret Loader
  node's dropdown.
- App data lives in an OS-appropriate directory (e.g. `~/.local/share/HouseGraph`
  on Linux); override the root with the `HOUSEGRAPH_HOME` environment variable or
  the `housegraph.home` system property.
