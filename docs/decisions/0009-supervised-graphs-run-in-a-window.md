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

The engine is headless but **graph execution is not**, in two ways:

1. There is no canvas-free path from a save file to a live `NodeGraph`. `place()`
   lives in `GraphCanvas`.
2. Several nodes keep runtime state in JavaFX controls. `TriggerRepeatingNode` uses
   a `javafx.animation.Timeline` as its clock and writes a status label and start
   button from `start()` — all null unless `createNodeContent()` ran.
   `EchoResourceNode` is the same, as are the Discord bot and web server nodes out
   of tree. `autoStartIfWasRunning()` would throw.

Node views are what run `createNodeContent()`, so a window that was never shown
would be a graph with half-initialised nodes.

**The practical cost:** the machine needs a logged-in GUI session, so automatic
login is part of the setup. And the jar bundles JavaFX's platform natives, so it
must be built on the machine that will run it.

**A true headless runner needs both gaps closed** — a canvas-free loader, and a
lifecycle seam keeping a node's running state in the node rather than in its
controls. The second is a cross-repo change touching every out-of-tree library.

The CLI's command surface is deliberately designed so that backend can slot in
behind `run` without changing how the daemon is operated.

One child per graph also buys isolation: a graph whose node wedges takes only itself
down, and restarting it does not interrupt the others.

**Reference:** [`../engine/remote-runtime.md`](../engine/remote-runtime.md)
