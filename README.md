# HouseGraph

A JavaFX desktop app for building **home-automation graphs**, with a focus on
computer-vision triggers. Wire nodes together on an infinite canvas — constants,
math, converters, control-flow branches and for-each loops — into graphs that react
to events. Graphs are saved as JSON and reopened between sessions.

Two kinds of connection run between nodes, and keeping them distinct is the core
design idea:

- **Data edges** carry a typed value from one node's output to another's input,
  pulled on demand.
- **Flow edges** carry no value — they define execution order, pushed when a
  trigger fires.

## Features

- **Infinite node canvas** — pan, zoom, rubber-band select, copy/paste, undo/redo,
  and manual edge routing.
- **Concurrent execution engine** — each trigger runs as an isolated concurrent
  run, so a slow node slows only its own branch. Branch fan-out, AND-barrier joins,
  for-each loops, and data-cycle detection. Per-node re-entrancy policy,
  concurrency limits and timeouts.
- **Extensible node libraries.** This repository ships the engine, the UI, and
  dependency-free primitives. Everything else is a **node library**: fetched from a
  GitHub repository and loaded at runtime, with no rebuild. A graph using a library
  you do not have still opens — the node is preserved exactly and offered for
  install, never silently lost. The app never installs on its own.
- **Encrypted secret store** (AES-256-GCM) so tokens and passwords never touch save
  files.
- **Logging** with levels and multiple independently-filterable outputs — console,
  a rotating log file, and an in-app **Logs** window that can be closed and
  reopened without losing history.
- **Runs unattended.** The same jar is a CLI that keeps graphs running on a
  dedicated machine, pulling them from a git repository.

First-party node libraries live in
[housegraph-nodes](https://github.com/jaymcole/housegraph-nodes):
`housegraph-discord` (a bot with text and slash commands), `housegraph-reolink`
(ONVIF/Reolink camera discovery and motion detection), `housegraph-web` (a LAN
web server on `<name>.local`, plus a Node.js process host), `housegraph-ml`
(local JVM image classification, no Python), `housegraph-github` (git sync),
`housegraph-squirrel` (an Arduino UNO R4 WiFi LED-matrix sign),
`housegraph-filesystem` and `housegraph-experimental`.

## Build & run

Requires nothing preinstalled beyond a JDK that Gradle can use; the build targets
**Java 21** with **JavaFX 21**.

```bash
./gradlew run
```

```bash
./gradlew test
```

The `main` you run is `io.github.jaymcole.housegraph.Launcher`.

### Standalone jar

```bash
./gradlew :app:shadowJar
```

```bash
java -jar app/build/libs/app-<version>.jar
```

Bundles JavaFX, `housegraph-api` and every other dependency. **It also bundles
JavaFX's native libraries for the platform it was built on**, so build it on the
machine that will run it. Node libraries are unaffected — they are fetched at
runtime, not bundled.

## Running unattended

The same jar is a command-line tool for running graphs continuously on a dedicated
machine. Keep your graphs in a GitHub repository; when you push, the machine pulls
and restarts them.

**→ [Setting up a HouseGraph server](docs/guides/server-setup.md)** — about 30
minutes.

| Command | Does |
| --- | --- |
| *(none)* | opens the editor on the last graph |
| `run <graph>` | opens the editor on one graph |
| `daemon [--once]` | sync loop plus process supervision |
| `sync [--force]` | pull the configured repositories now; starts nothing |
| `plugins list \| install <url> \| update [id...]` | node libraries from the terminal |
| `check <graph.json>` | which libraries a graph needs, and whether you have them |
| `doctor` | check git, the data directory, config and installed libraries |

Two things worth knowing before you start:

- **Build the jar on the machine that will run it.**
- **A trigger only resumes if it was running when you saved the graph.** The server
  opens your graph; it does not press Start. This is the usual reason a deployed
  graph sits there doing nothing.

Graphs run in the normal windowed app, supervised as child processes, so the
machine needs a logged-in GUI session.

## Documentation

| Section | For |
| --- | --- |
| [docs/guides/](docs/guides/) | Using HouseGraph: setup, node libraries, secrets, servers, troubleshooting |
| [docs/nodes/](docs/nodes/) | Writing nodes and publishing node libraries |
| [docs/engine/](docs/engine/) | Engine internals: execution, concurrency, save format, plugin runtime |
| [docs/decisions/](docs/decisions/) | Why things are the way they are |

New here? [Getting started](docs/guides/getting-started.md). Contributing?
[`CLAUDE.md`](CLAUDE.md) has the architecture map, the invariants, and the rule
that changes must keep the docs in sync.

## Configuration

App data lives in an OS-appropriate directory — `%APPDATA%\HouseGraph` on Windows,
`~/Library/Application Support/HouseGraph` on macOS,
`~/.local/share/HouseGraph` on Linux. Override the root with `HOUSEGRAPH_HOME`, the
`housegraph.home` system property, or `--home` on any CLI command.

A gitignored `.env` (see [`.env.example`](.env.example)) seeds the Secret Loader
node's dropdown.
