# `graph/` — the execution engine and node model

Full context: [`docs/architecture/graph-engine.md`](../../../../../../../../docs/architecture/graph-engine.md)
and [`docs/architecture/nodes.md`](../../../../../../../../docs/architecture/nodes.md).
Start at the repo-root [`CLAUDE.md`](../../../../../../../../CLAUDE.md) if you haven't.

This package is the headless core. Hold these invariants when editing here:

- **Never import JavaFX into this package.** Engine → UI notifications go through
  the injected `callbackExecutor` and the `GraphExecutionListener` interface only.
  Breaking this breaks headless testing.
- **Data vs. flow stay separate.** `NodeVariable`/`Edge` carry typed values
  (pulled on demand); `FlowPort`/`FlowEdge` carry only control order (pushed on
  trigger). Do not fold one into the other.
- **Threading is load-bearing.** Runs execute concurrently on a virtual-thread
  executor, each carrying its own isolated `ExecutionContext` (status, visited set,
  activated ports, computed values). Flow is fire-and-forget (a node schedules its
  downstream and doesn't wait); a node's resolution is guarded by a *per-run* monitor
  (`ExecutionContext.lockFor`) that dedups shared data deps and detects cycles via the
  `IN_PROGRESS` status. If you touch `resolve`/`execute`/locking or the context, re-read
  the `NodeGraph`/`ExecutionContext` Javadoc and update it **and** the graph-engine doc.
- **Persistence discipline lives in `NodeVariable`.** Only manually-authored,
  non-secret, non-transient values are saved (`isPersistentValue`). Keep it that
  way; computed values recompute on load.
- **`BaseNode` lifecycle hooks are a public contract.** If you add/change a hook
  (`onActivated`/`onRemoved`/`onInputEdgeAdded`/…), update its Javadoc and
  `docs/architecture/nodes.md`.

Concrete node subclasses live in `nodes/` — see that folder's `CLAUDE.md` for the
add-a-node recipe.

**When you change engine behavior, update the `NodeGraph`/`BaseNode` Javadoc and
the two docs linked above.**
