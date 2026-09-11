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
- **Modules** — publish a graph as a reusable node. Its Module Input/Output/Entry/Exit
  markers become the ports other graphs see, and it runs as a nested graph of its own
  rather than being copied in, so editing it reaches every graph that uses it. A
  module referenced by a graph on a machine that does not have it is kept exactly as
  saved rather than lost. See [docs/guides/modules.md](docs/guides/modules.md).
- **Watch mode** — **Run ▸ Watch Speed** slows every run to a set pace, so a cascade
  can be followed node by node as it fires instead of finishing in a blink. Off by
  default and never saved with the graph.
- **Groups** — wrap part of a canvas in a labelled, coloured frame, so a large graph
  can be read at a glance. Dragging a frame carries everything inside it, and frames
  nest. Membership is the rectangle: nothing to maintain, and nothing that can go
  stale against the canvas.
- **Several graphs at once** — **File ▸ Open in New Window…** opens a graph beside
  the one being edited rather than replacing it. Each window is its own document with
  its own file, undo history and running graph; closing one shuts down only what that
  graph had running.
- **Image export** — writes a PNG of each distinct graph on the canvas, so a graph
  can be shared or documented as a picture rather than a screenshot.
- **Encrypted secret store** (AES-256-GCM) so tokens and passwords never touch save
  files.
- **Logging** with levels and multiple independently-filterable outputs — console,
  a rotating log file, and an in-app **Logs** window that can be closed and
  reopened without losing history. Warnings and errors can also be **forwarded to a
  Discord channel**, at a level of their own, so a machine nobody is watching can
  still say something.
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
| `nodes list [--json]` | installed node types, or the full machine-readable catalog |
| `nodes check <graph.json>` | whether a graph's nodes still match what's installed |
| `schema [graph\|catalog]` | the JSON Schema for the save format or the node catalog |

Two things worth knowing before you start:

- **Build the jar on the machine that will run it.**
- **A trigger only resumes if it was running when you saved the graph.** The server
  opens your graph; it does not press Start. This is the usual reason a deployed
  graph sits there doing nothing.

Graphs run in the normal windowed app, supervised as child processes, so the
machine needs a logged-in GUI session.

## Releasing

Releasing is automatic: merging a pull request into `main` triggers
`.github/workflows/auto-tag.yml`, which tags the resulting commit
`v<major>.<minor>.<patch>`, bumping the patch number by default. Put `#minor` or
`#major` anywhere in the PR title to bump one of those instead (and reset the
parts below it to zero) — e.g. a title of `Add plugin update command #minor`
bumps the minor version.

Pushing a `v*` tag — whether from `auto-tag.yml` or manually — triggers
`.github/workflows/release.yml`, which:

1. Checks out, sets up JDK 21, on `ubuntu-latest`, `macos-latest` and
   `windows-latest` in parallel.
2. On each, runs `./gradlew build -Pversion=<tag without the v>` — builds and tests
   both modules at that version, producing a shadow jar that bundles that runner's
   JavaFX natives per the platform caveat above (the macOS jar is built on Apple
   Silicon, so it won't run natively on an Intel Mac).
3. Attaches all three jars — `app-<version>-linux.jar`, `app-<version>-macos.jar`,
   `app-<version>-windows.jar` — to a single GitHub Release, with auto-generated
   release notes.

`housegraph-api` isn't released through this workflow: JitPack publishes it
directly from the same tag (see [`jitpack.yml`](jitpack.yml) and
[`docs/nodes/publishing-a-library.md`](docs/nodes/publishing-a-library.md)).

## Documentation

| Section | For |
| --- | --- |
| [docs/guides/](docs/guides/) | Using HouseGraph: setup, node libraries, modules, secrets, servers, troubleshooting |
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
