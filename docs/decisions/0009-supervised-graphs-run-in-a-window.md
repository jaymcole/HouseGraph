# 0009 — Supervised graphs run in the real windowed app

## Context

The point of the daemon is running graphs on a machine with no one at the keyboard.
The obvious implementation is a headless runner. The engine is already headless:
`NodeGraph` has no JavaFX imports and dispatches its callbacks through an injectable
executor.

## Decision

The daemon supervises **one child JVM per graph, running the ordinary JavaFX app**
via `java -jar app.jar run <graph>` — window and all, exactly as it would be run by
hand.

## Consequences

The engine is headless but **graph execution was not**, in three ways. All have
since been closed in this repository:

1. There was no canvas-free path from a save file to a live `NodeGraph`: `place()`
   lived in `GraphCanvas`. *Since closed* — `GraphLoader` builds a snapshot's nodes
   and edges onto a `NodeGraph` with no view involved, and the canvas only draws
   what it returns.
2. Several nodes kept runtime state in JavaFX controls. `TriggerRepeatingNode` used
   a `javafx.animation.Timeline` as its clock and wrote a status label and start
   button from `start()` — all null unless `createNodeContent()` ran, so
   `autoStartIfWasRunning()` would throw. *Since closed* — a node owns its running
   flag, drives its clock with `sdk.NodeTimer`, and reaches its controls only
   through `BaseNode.present(...)`, which discards the update when nothing is
   drawing that node. Whether a node has a view is asked of the node, never of the
   process, because a graph used from inside another graph has viewless interior
   nodes in a fully windowed app.
3. Nothing opened a graph, resumed it and stayed alive without a canvas:
   `GraphCanvas.loadSnapshot` was the only caller of `autoStartIfWasRunning()`, and
   `run` meant the GUI. *Since closed* — `housegraph run --headless <graph>` is that
   runner, and `run <graph>` still opens the editor.

**The decision still stands, but for one reason rather than the original three.**
The seam in (2) was made purely additive so out-of-tree libraries keep compiling
untouched, which means a library that has not adopted it — the Discord bot and web
server nodes among them — is exactly as viewless-unsafe as before. The runner isolates
each node's resume, so such a node fails alone and loudly, but a graph whose point is
its Discord bot is not usefully running without it. Flipping `GraphProcess` to
headless children is a separate change, worth making once those libraries have
adopted the seam; shipping a runner and changing what every deployed server does are
two different risks.

**The practical cost:** the machine needs a logged-in GUI session, so automatic
login is part of the setup. And the jar bundles JavaFX's platform natives, so it
must be built on the machine that will run it.

The CLI's command surface was deliberately designed so that backend could slot in
behind `run` without changing how the daemon is operated, and it did: the runner is a
flag on `run`, and `GraphProcess.defaultLauncher` is untouched.

One child per graph also buys isolation: a graph whose node wedges takes only itself
down, and restarting it does not interrupt the others.

**Reference:** [`../engine/remote-runtime.md`](../engine/remote-runtime.md)
