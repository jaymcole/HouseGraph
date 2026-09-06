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

The engine is headless but **graph execution was not**, in two ways. Both have
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

**The decision still stands, for two reasons that are not the original ones.**
Nothing yet opens a graph and resumes it without a canvas: `GraphCanvas.loadSnapshot`
remains the only caller of `autoStartIfWasRunning()`, and `run` still means the GUI.
And the seam was made purely additive so out-of-tree libraries keep compiling
untouched, which means a library that has not adopted it — the Discord bot and web
server nodes among them — is exactly as viewless-unsafe as before. A headless runner
would have to wait for those either way.

**The practical cost:** the machine needs a logged-in GUI session, so automatic
login is part of the setup. And the jar bundles JavaFX's platform natives, so it
must be built on the machine that will run it.

The CLI's command surface is deliberately designed so that backend can slot in
behind `run` without changing how the daemon is operated.

One child per graph also buys isolation: a graph whose node wedges takes only itself
down, and restarting it does not interrupt the others.

**Reference:** [`../engine/remote-runtime.md`](../engine/remote-runtime.md)
