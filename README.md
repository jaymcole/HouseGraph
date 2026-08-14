# HouseGraph

A JavaFX desktop app for building **home-automation graphs**, with a focus on
computer-vision triggers. Wire nodes together on an infinite canvas — constants,
math, converters, control-flow branches and for-each loops — into graphs that
react to events. Graphs are saved as JSON and reopened between sessions.

Two kinds of connection run between nodes, and keeping them distinct is the core
design idea:

- **Data edges** carry a typed value from one node's output to another's input
  (pulled on demand).
- **Flow edges** carry no value — they define execution order (pushed when a
  trigger fires).

## Features

- Infinite, pannable, zoomable node canvas with rubber-band select, copy/paste,
  undo/redo, and manual edge routing.
- **Extensible node libraries.** This repository ships the engine, the UI, and a
  set of dependency-free primitives. Everything else — chat bots, IoT devices, and
  more — is a **node library**: fetched from a GitHub repository (**Node
  Libraries…**) and loaded at runtime, with no rebuild of the app. Write your own
  starting from [housegraph-plugin-template](https://github.com/jaymcole/housegraph-plugin-template).
  A graph that uses a library you don't have installed still opens — the node is
  preserved exactly and offered for install, never silently lost.
- First-party node libraries, in [housegraph-nodes](https://github.com/jaymcole/housegraph-nodes):
  a **Discord** bot with modular text/slash commands (via JDA), an **IoT**
  library driving an Arduino UNO R4 WiFi LED-matrix sign, a **Camera** library
  for ONVIF/Reolink IP-camera discovery and motion detection, a **Web**
  library with a **Web Server** node that hosts a directory of static files on
  the LAN as `<name>.local` (via jmdns multicast DNS) — optionally with a
  **Data Store** node giving the hosted site shared, server-side persistence
  over a `/api/data` endpoint — and a **Node Server** node for launching an
  external Node.js server the same way, and an **ML** library with local,
  JVM-native image classification (via Deep Java Library — no Python): an
  **Animal Classifier** node tells a squirrel from a bird from a frame, with
  weights downloaded on first use and run locally thereafter.
- Concurrent, thread-safe execution engine with branch fan-out, for-each loops
  (a **For Each** node runs its body once per list item), and data-cycle detection.
- Encrypted secret store (AES-256-GCM) so tokens and passwords never touch save
  files, whether the node reading them ships with the app or comes from a library.
- Logging with levels and multiple outputs (console, a log file, and an in-app
  **Logs** window), each independently filterable. The log window is standalone
  and can be closed and reopened without losing history.

## Build & run

Requires nothing preinstalled beyond a JDK that Gradle can use; the build targets
**Java 21** with **JavaFX 21**. Two Gradle modules: `housegraph-api` (published,
what a node library compiles against) and `app` (the desktop program).

```bash
./gradlew run     # launch the app (delegates to :app:run)
./gradlew test    # run the JUnit 5 test suite in both modules
```

The `main` you run is `io.github.jaymcole.housegraph.Launcher`.

### Standalone jar

To run the app without an IDE, build a self-contained executable jar (bundles
JavaFX, `housegraph-api`, and all other dependencies via the Shadow plugin):

```bash
./gradlew :app:shadowJar
java -jar app/build/libs/app-<version>.jar
```

Out-of-tree node libraries are unaffected — they're still fetched/loaded at
runtime, not bundled into this jar. See
[docs/architecture/plugins.md](docs/architecture/plugins.md).

## Running unattended

The same jar is also a command-line tool, for running graphs continuously on a
dedicated machine (a Mac mini in a cupboard). Keep your graphs in a GitHub
repository; when you push, the machine pulls and restarts them.

**→ [Setting up a HouseGraph server](docs/remote-server-setup.md)** — the
step-by-step guide, about 30 minutes.

```bash
java -jar app/build/libs/app-<version>.jar doctor   # is this machine ready?
java -jar app/build/libs/app-<version>.jar sync     # pull now, report what changed
java -jar app/build/libs/app-<version>.jar daemon   # poll and keep the graphs running
```

| Command | Does |
| --- | --- |
| *(none)* | opens the editor on the last graph, exactly as before |
| `run <graph>` | opens the editor on one graph |
| `daemon [--once]` | sync loop plus process supervision |
| `sync [--force]` | pull the configured repositories now; starts nothing |
| `plugins list \| install <url> \| update [id...]` | node libraries from the terminal |
| `check <graph.json>` | which libraries a graph needs, and whether you have them |
| `doctor` | check git, the data directory, config and installed libraries |

Configure it in `config/remote.json` under the data directory (`doctor` prints
where). Graphs are listed in a `housegraph.json` at the root of the repository
being tracked. A macOS LaunchAgent template is in
[`extras/launchd/`](extras/launchd/).

Two things worth knowing before you start:

- **Build the jar on the machine that will run it.** It bundles JavaFX's native
  libraries for the platform it was built on, so a jar copied between platforms
  won't launch.
- **A trigger only resumes if it was running when you saved the graph.** The
  server opens your graph; it doesn't press Start. This is the usual reason a
  deployed graph sits there doing nothing.

**Graphs still run in the normal windowed app**, supervised as child processes, so
the machine needs a logged-in GUI session — automatic login on a Mac mini is the
intended setup. The full design, including why it isn't headless yet and what it
would take, is in
[docs/architecture/deployment.md](docs/architecture/deployment.md).

## Documentation

Start with **[`CLAUDE.md`](CLAUDE.md)** — the architecture map, the standards the
code holds itself to, and (importantly) the rule that **changes must keep the docs
in sync**.

Task guides:

| Guide | Covers |
| --- | --- |
| [remote-server-setup.md](docs/remote-server-setup.md) | setting up a machine to run your graphs 24/7 from a git repository |

Subsystem deep-dives live in **[`docs/architecture/`](docs/architecture/)**:

| Doc | Covers |
| --- | --- |
| [overview.md](docs/architecture/overview.md) | modules, layering, dependency direction, graph lifecycle |
| [graph-engine.md](docs/architecture/graph-engine.md) | execution model, threading, cycle detection |
| [nodes.md](docs/architecture/nodes.md) | the node model and how to add a node |
| [ui.md](docs/architecture/ui.md) | canvas, views, undo, save/load |
| [resources.md](docs/architecture/resources.md) | named resources & event pub/sub |
| [storage-and-secrets.md](docs/architecture/storage-and-secrets.md) | on-disk layout, encrypted secrets |
| [logging.md](docs/architecture/logging.md) | log levels, sinks, and the log window |
| [integrations.md](docs/architecture/integrations.md) | a record of every integration that used to live in this repository, now all out-of-tree node libraries |
| [plugins.md](docs/architecture/plugins.md) | out-of-tree node libraries: fetching, loading, and writing your own |
| [deployment.md](docs/architecture/deployment.md) | running unattended: the CLI, git sync, and process supervision |
| [testing.md](docs/architecture/testing.md) | test conventions |

High-traffic packages (`graph/`, `graph/nodes/`, `ui/`) also carry their own
`CLAUDE.md` with local rules.

## Configuration

- `.env` (gitignored; see [`.env.example`](.env.example)) seeds the Secret Loader
  node's dropdown.
- App data lives in an OS-appropriate directory (e.g. `~/.local/share/HouseGraph`
  on Linux); override the root with the `HOUSEGRAPH_HOME` environment variable,
  the `housegraph.home` system property, or `--home` on any CLI command. Installed
  node libraries live under `plugins/` inside that same directory, and synced graph
  repositories under `remotes/`.
