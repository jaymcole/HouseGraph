# 0003 — Teardown is two phases with a per-node budget

## Context

`onRemoved()` was the only teardown hook, and `App.stop()` gave the whole graph a
flat 15-second budget.

That cannot hold. Teardown which waits on the outside world — reaping a child
process tree, withdrawing an mDNS registration, logging a bot out — costs seconds
*each*, so any fixed total is one added server node away from being too small. When
it expired the JVM exited mid-teardown and orphaned exactly the child processes the
budget existed to clean up.

Bounding the work was not simply a matter of adding a timeout, because the two
kinds of teardown want opposite threads. Stopping a `Timeline` or touching a
control must happen on the FX thread. Work you intend to bound must not run on the
thread you are standing on — and `dispose()` runs *on* the FX thread inside
`App.stop()`, so marshalling back to it would never run.

## Decision

Split teardown into two hooks.

- `onRemoved()` — the fast, thread-affine half. Runs on the removing thread, in
  order, unbounded.
- `releaseResources()` — the slow half. Runs on a worker thread, **concurrently
  with every other node's**, under one per-node deadline
  (`DEFAULT_RELEASE_TIMEOUT`, 15s).

`releaseResources()` is a concrete hook with a default no-op, so adding it broke
nothing.

Shutdown waits are derived rather than guessed, and nest strictly:

```
node teardown  ~11s  <  NodeGraph release  15s  <  App.stop()  25s  <  Supervisor  40s
```

## Consequences

Shutdown costs the **slowest** node rather than the sum, a number that no longer
grows with the graph.

**Each layer must be strictly longer than the one inside it.** Invert any pair and
the outer wait truncates the inner one, reintroducing the orphaning this fixed.

A node library only gets the bound if it opts in. Blocking work left in
`onRemoved()` still runs unbounded on the shutdown thread. A library built against
an older API keeps working; it just does not get the bound.

Related: `App` gained a shutdown hook at the same time. JavaFX calls
`Application.stop()` on a platform exit but **not** on a signal, and a signal is
how the supervisor restarts a graph — so before the hook, every `kill` and every
logout skipped teardown entirely.

**Reference:** [`../engine/node-lifecycle.md`](../engine/node-lifecycle.md)
