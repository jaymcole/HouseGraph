# `graph/` — the execution engine and node model

Full context: [`docs/engine/`](../../../../../../../../../docs/engine/).
Start at the repo-root [`CLAUDE.md`](../../../../../../../../../CLAUDE.md) if you
haven't.

This package is the headless core. Hold these invariants when editing here:

- **Never import JavaFX into this package.** Engine-to-UI notifications go through
  the injected `callbackExecutor` and the `GraphExecutionListener` interface only.
  Breaking this breaks headless testing.
- **Data and flow stay separate.** `NodeVariable`/`Edge` carry typed values, pulled
  on demand; `FlowPort`/`FlowEdge` carry only control order, pushed on trigger.
- **Threading is load-bearing.** Runs execute concurrently on a virtual-thread
  executor, each carrying its own isolated `ExecutionContext`. Flow is
  fire-and-forget — a node schedules its downstream and does not wait — and a
  node's resolution is guarded by a *per-run* monitor (`ExecutionContext.lockFor`)
  that dedups shared data dependencies and detects cycles via `IN_PROGRESS`. If you
  touch `resolve`/`execute`/locking or the context, re-read the
  `NodeGraph`/`ExecutionContext` Javadoc and update it **and** the engine docs.
- **Persistence discipline lives in `NodeVariable`.** Only manually-authored,
  non-secret, non-transient values are saved (`isPersistentValue`). Computed values
  recompute on load.
- **`BaseNode` lifecycle hooks are a public contract.** `housegraph-api` is
  compiled against by out-of-tree libraries. The API is **not stable yet**, so a
  breaking change is allowed — but it means rebuilding `housegraph-nodes` and the
  template, so do that in the same pass. Prefer a concrete hook with a default over
  a new abstract method; it avoids the rebuild.

Concrete node subclasses live in `app`'s `graph/nodes/` — see that folder's
`CLAUDE.md` for the add-a-node recipe.

**When you change engine behaviour, update the `NodeGraph`/`BaseNode` Javadoc and
the matching page under [`docs/engine/`](../../../../../../../../../docs/engine/).**
