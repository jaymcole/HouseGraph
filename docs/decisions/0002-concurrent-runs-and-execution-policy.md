# 0002 — Concurrent runs, and per-node execution policy

## Context

Originally a run held per-node state — status, visited set, computed values — on
the shared node objects, and flow fan-out joined before continuing. That serialized
everything: a slow node, such as an image analysis or an LLM call, stalled every
other trigger in the graph.

Making runs concurrent raised a second problem. One trigger often fans out into
branches with very different timing — a slow analysis and a fast status post. A
single whole-run policy at the entry node cannot express "drop re-triggers on the
slow branch but queue them on the fast one", because both branches live in one run.

## Decision

**Per-run isolation.** All per-run state moved into an `ExecutionContext` bound to
the run, including a computed-value overlay. Authored values stay on the node,
read-only during a run. Resolution locks on a monitor from the context rather than
on the node.

**Fire-and-forget flow.** A node schedules its downstream nodes and does not wait.
Reconvergence therefore has no implicit barrier.

**Policy at two scopes.** `ExecutionPolicy` is enforced both at an entry node,
gating a whole re-triggered run, and mid-cascade through a `ReentryGate`, gating
re-entry of one node's own `process()`.

Delivered in stages, in the commit series named *per-node-execution-policy stage
a/b/c*: the value overlay, then the `Run` model with fire-and-forget flow, then the
flow-join barrier.

## Consequences

A slow node slows only its own run. `PARALLEL` is genuinely parallel, because the
resolution lock is per-run.

**Reconvergence changed semantics.** An ordinary fan-in node now fires on the first
branch to arrive, behaving as an OR/merge. Waiting for all branches requires an
explicit flow join (`JoinNode`).

**A compatibility consequence of the `QUEUE` default:** two `PARALLEL` runs that
fan into a shared downstream node serialize at that node unless it is itself set
`PARALLEL`. Before mid-cascade gating existed they ran it concurrently. Graphs with
only non-`PARALLEL` entries are unaffected, since no concurrent runs start.

The mid-cascade gate is **process-scoped, not subgraph-scoped** — it covers a
node's own `process()`, not its downstream subtree. Extending it would need
per-activation subtree tracking and cancellation scoping, and interacts with joins.
Deliberately out of scope.

`RESTART` is cooperative at both scopes. A node that never polls its
`ProcessContext` stops only at the next node boundary.

**Reference:** [`../engine/execution-policy.md`](../engine/execution-policy.md),
[`../engine/concurrency.md`](../engine/concurrency.md)
