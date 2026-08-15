# Execution policy

Every node carries an `ExecutionPolicy` — a `volatile` field on `BaseNode`,
default `QUEUE` — governing what happens when the node is re-entered while work it
started is still in flight.

| Policy | Behaviour |
| --- | --- |
| `DROP` | Ignore the new arrival while one is running or queued. |
| `RESTART` | Cancel the in-flight work and run fresh with the newest inputs. |
| `QUEUE` (default) | Run after the in-flight one, **coalescing to the latest**: at most one is kept pending, so a burst collapses to a single follow-up rather than an unbounded backlog. |
| `PARALLEL` | Start an independent concurrent run every time. No single-flight gate, no coalescing. |

`RESTART` is **cooperative** at both scopes. It flags the run cancelled — surfaced
through the node's `ProcessContext`, and mid-cascade by interrupting the holder —
but a node already inside `process()` that never checks stops only at the next
node boundary. It does not forcibly kill a `process()`, and mid-cascade it does not
retract downstream work already scheduled from the superseded activation.

## Why two scopes

One trigger often fans out into branches with very different timing: a slow branch
analysing an image, a fast branch posting a status. The user may want the slow
branch to **drop** re-triggers while busy and the fast branch to **queue** them. A
single whole-run policy at the entry node cannot express that, because both
branches live in one run.

So the same four values are enforced at two scopes. The difference is what "in
flight" means and what gets dropped, queued or restarted.

## Entry-node scope — the whole run

An **entry-point node** is one a trigger fires directly: a Trigger button, a
Repeating Trigger, an event listener, an inbound command.
`BaseNode.isExecutionEntryPoint()` marks them, defaulting to "has a flow-out but
no flow-in". A node that self-triggers *and* has a flow-in overrides it to `true`.

When an entry node is triggered again while a run it started is still in flight,
its policy gates the whole run. `NodeGraph.execute(node, prepare)` reads the policy
and applies it through `EntryExecution`, one per entry node:

- `DROP` / `QUEUE` / `RESTART` keep a single in-flight run, with a single coalesced
  pending run for `QUEUE` and `RESTART`.
- `PARALLEL` starts a new run unconditionally.

Different entry nodes always run concurrently. A per-run `PassToken` that `RESTART`
flips, and `Run.fire` checks at each node boundary, stops a superseded run's
remaining cascade.

## Mid-cascade scope — one node's `process()`

A node reached along a flow edge during a run re-applies its policy at a narrower
grain, through a `ReentryGate` keyed by node in `NodeGraph.reentryGates` and
consulted in `Run.fire`.

The gate is **held only while some run is inside that node's `process()`**. It is
acquired just before `process()` and released the instant `process()` returns —
before downstream is scheduled — so the gated window is exactly that node's own
work, nothing beyond it. The run's entry node is not gated here; its re-entrancy
was already decided at entry scope.

When a second run's flow reaches a held gate:

- **`DROP`** — abandon this branch. The node does not run and nothing past it is
  scheduled in this run.
- **`QUEUE`** — park as the single coalesced waiter, on a cheap virtual thread. A
  newer arrival evicts the older one, which returns and abandons its branch. When
  the holder's `process()` returns, the gate passes straight to the waiter.
- **`RESTART`** — additionally interrupt the holder, then queue as above.
- **`PARALLEL`** — never gated; the arrival runs the node concurrently.

A node still fires at most once per run (`flowVisited`). The gate is only about
*different* runs overlapping on it.

## How the two compose

The entry policy decides whether concurrent runs start at all, so a mid-cascade
gate only sees overlap when the entry is `PARALLEL`, or when two distinct entry
nodes feed a shared node.

The motivating configuration:

> Trigger `PARALLEL`, the slow branch's node `DROP`, the fast branch's node
> `QUEUE`. Each trigger starts its own run; the fast branch posts every time; the
> slow branch drops a new arrival whenever its previous `process()` is still
> running.

**A consequence of the `QUEUE` default:** two `PARALLEL` runs that fan into a
shared downstream node **serialize** at it unless that node is itself set
`PARALLEL`. Graphs whose entries are all non-`PARALLEL` never contend, because no
concurrent runs start.

## Scope limit

The gate is **process-scoped, not subgraph-scoped**: it covers a node's own
`process()`, not its downstream subtree. For the case this exists for — one slow
node making an external call — that is exactly the slow part. Gating a node until
its whole downstream branch quiesced would need per-activation subtree tracking and
cancellation scoping, and interacts with joins and reconvergence. That is a
possible extension, deliberately out of scope.

## Where it lives

| Concern | Code |
| --- | --- |
| Policy enum and semantics | `graph/ExecutionPolicy.java` |
| Per-node field and accessors | `graph/BaseNode.java` |
| Entry-scope gate | `graph/NodeGraph.java` — `execute`, `EntryExecution` |
| Mid-cascade gate | `graph/NodeGraph.java` — `reentryGates`, `ReentryGate`, `Run.fire` |
| Per-run isolation | `graph/ExecutionContext.java` |
| Persistence | `ui/io/GraphFileIO.java` — `executionPolicy`; missing reads as `QUEUE` |
| UI selector and glyph | `ui/view/NodeView.java`, `ui/view/ExecutionPolicyIcons.java` |
| Tests | `graph/NodeGraphTest.java`, `ui/io/GraphFileIOTest.java` |

The UI shows the policy glyph and its right-click submenu for any node that
participates in flow, at both scopes — see [ui-layer.md](ui-layer.md). Authoring
guidance is in [`../nodes/execution-tuning.md`](../nodes/execution-tuning.md).

---

**When you change this, update…** this file together with the `NodeGraph` /
`ExecutionContext` / `ExecutionPolicy` / `BaseNode` Javadoc whenever you change
policy semantics, either gate, or how the two scopes compose.
