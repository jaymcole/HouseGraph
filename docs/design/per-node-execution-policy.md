# Design: per-node execution policy & concurrent runs

This is the design record for the concurrent-runs execution model and the per-node
execution-policy work built on top of it. It is the narrative companion to the Javadoc on
`NodeGraph`, `ExecutionContext`, `ExecutionPolicy`, and `BaseNode`; where the two disagree, the
code wins and this doc should be fixed. For the day-to-day engine overview see
[`../architecture/graph-engine.md`](../architecture/graph-engine.md).

## Why this exists

A run is one trigger firing and everything that cascades from it. The engine runs each trigger as
an **isolated, concurrent run** on virtual threads, so a slow node (an LLM call, a camera) only
slows its own run while other triggers keep firing. Two problems fall out of that, and this design
solves both:

1. **Isolation.** Concurrent runs must not clobber one another's per-run state (statuses, the
   flow-visited set, activated ports, computed data values). That state lives in a per-run
   `ExecutionContext`, off the shared node/graph objects. This was delivered in stages A–C (the
   commit series named *per-node-execution-policy stage a/b/c*): the value overlay, the
   `NodeGraph.Run` model with fire-and-forget flow, and the flow-join AND-barrier.

2. **Per-branch re-entrancy.** One trigger often fans out into branches with very different timing
   — a slow branch (analyse an image with an LLM) and a fast branch (post a status to Discord).
   The user may want the slow branch to **drop** re-triggers while it's busy but the fast branch to
   **queue** them. A single whole-run policy at the entry node can't express that, because both
   branches live in one run. This is what per-node (mid-cascade) execution policy adds.

## The concurrent-runs model (foundation)

- **`ExecutionContext`** — one per run, holds that run's node statuses, `flowVisited` set,
  activated flow-ports, per-node resolution monitors, join-arrival counts, and the **computed-value
  overlay**. A `NodeVariable`'s authored value stays on the node (read-only during a run, safe to
  share); its computed value lives in the context bound to the current thread. So two runs over the
  same node never see each other's computed values.
- **`NodeGraph.Run`** — one run's context + a `PassToken` (cancellation) + a `pending` firing
  counter. Flow is **fire-and-forget**: a node resolves its data synchronously, runs `process()`,
  then *schedules* its activated downstream nodes and does not wait. Linear order still holds (a
  node fires downstream only after its own `process()`), but a fan-out no longer joins. The run ends
  when `pending` hits zero.
- **Per-run resolution lock** — `resolveInternal` synchronizes on `ExecutionContext.lockFor(node)`,
  not the node object, so two concurrent runs sharing a node don't serialize on each other's
  `process()`. That's what makes `PARALLEL` genuinely parallel.
- **Reconvergence** — with no implicit join, an ordinary fan-in fires on the first branch to arrive
  (OR/merge); to wait for **all** branches use a flow join (`BaseNode.isFlowJoin()`, concrete
  `JoinNode`).

## Execution policy, two scopes

`ExecutionPolicy` (`DROP` / `RESTART` / `QUEUE` / `PARALLEL`, default `QUEUE`) is a `volatile` field
on every `BaseNode`. It is enforced at two scopes.

### Entry-node scope — the whole run

`NodeGraph.execute(node, prepare)` reads the policy on the node being triggered and gates the whole
run via `EntryExecution` (one per entry node): a single in-flight run for `DROP`/`QUEUE`/`RESTART`
(with a single coalesced pending run for `QUEUE`/`RESTART`), or an unconditional new run for
`PARALLEL`. This is the original behavior and is unchanged.

### Mid-cascade scope — one node's `process()`

A node reached along a flow edge during a run re-applies its policy at a **process-scoped** grain,
via a `ReentryGate` (one per node, keyed in `NodeGraph.reentryGates`), consulted in `Run.fire`:

- The gate is **held** only while some run is inside that node's `process()`. It is acquired just
  before `process()` and released the instant `process()` returns — **before** downstream is
  scheduled — so the gated window is exactly the node's own `process()`, nothing downstream.
- The run's **entry node is not gated here** (its re-entrancy was already decided at entry scope);
  only nodes reached along a flow edge are.
- When a second run's flow reaches a **held** gate:
  - `DROP` — abandon this branch. The node isn't run and nothing past it in this run is scheduled.
  - `QUEUE` — park as the single coalesced **waiter** (a cheap virtual thread). A newer arrival
    evicts the older waiter (coalesce-to-latest); the evicted one returns and abandons its branch.
    When the holder's `process()` returns, the gate is handed straight to the waiter, which then
    runs the node.
  - `RESTART` — additionally interrupt the holder's `process()` (cooperative — a `process()` that
    ignores interruption isn't forcibly stopped; already-scheduled downstream from the old
    activation is not retracted), then queue like `QUEUE`.
  - `PARALLEL` — never gated; the arrival runs the node concurrently.

### How the two scopes compose

The entry policy decides whether concurrent runs start at all; the mid-cascade gate only ever sees
overlap when the entry is `PARALLEL` (or when two distinct entry nodes feed a shared node). The
motivating example:

> Trigger = `PARALLEL`, slow branch's node = `DROP`, fast branch's node = `QUEUE`.
> Each trigger starts its own run; the fast branch posts every time; the slow branch drops a new
> arrival whenever its previous `process()` is still running.

**Backward-compatibility note.** Because the default policy is `QUEUE` and it now gates mid-cascade,
two `PARALLEL` runs that fan into a shared downstream node **serialize** at that node unless it is
itself set `PARALLEL`. Before this change they ran that node's `process()` concurrently. Graphs
with only single-trigger (non-`PARALLEL`) entries are unaffected — no concurrent runs means the
mid-cascade gate never contends.

## Scope limits (intentional, v1)

- **Process-scoped, not subgraph-scoped.** The gate covers a node's own `process()`, not its whole
  downstream subgraph. For the common "one slow node" case (an LLM/camera call) that is exactly the
  slow part. Gating a node until its entire downstream branch quiesces would need per-activation
  subtree tracking and cancellation scoping and interacts with joins/reconvergence — a possible
  future extension, deliberately out of scope here.
- **RESTART is cooperative** at both scopes — it interrupts, it doesn't forcibly kill a `process()`,
  and mid-cascade it doesn't retract already-scheduled downstream from the superseded activation. A
  node that wants to bail promptly polls its `ProcessContext` (`ctx.checkCancelled()`), which reports
  the run's cancellation even for a CPU-bound loop with no interruptible call (entry-scope RESTART
  cancels the run's token but does not interrupt the thread); a node that never checks stops only at
  the next node boundary.

## Where it lives

| Concern | Code |
| --- | --- |
| Policy enum & semantics | `graph/ExecutionPolicy.java` |
| Per-node policy field + accessors | `graph/BaseNode.java` (`executionPolicy`) |
| Entry-scope gate | `graph/NodeGraph.java` — `execute`, `EntryExecution` |
| Mid-cascade gate | `graph/NodeGraph.java` — `reentryGates`, `ReentryGate`, `Run.fire` |
| Per-run isolation | `graph/ExecutionContext.java` |
| Persistence | `ui/GraphFileIO.java` (writes `executionPolicy` for every node; missing = `QUEUE`) |
| UI (selector + glyph) | `ui/NodeView.java`, `ui/ExecutionPolicyIcons.java` (shown for any node that participates in flow) |
| Tests | `graph/NodeGraphTest.java` (entry + mid-cascade policy), `ui/GraphFileIOTest.java` |

---

**When you change this**, update this file together with the `NodeGraph` / `ExecutionContext` /
`ExecutionPolicy` / `BaseNode` Javadoc and [`../architecture/graph-engine.md`](../architecture/graph-engine.md).
