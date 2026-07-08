# Graph Engine

The engine lives in `graph/` and is centered on `NodeGraph`. It owns a set of
`BaseNode`s and the connections between them, and it drives their execution. It
**never imports JavaFX** — that is a load-bearing rule, explained below.

Source of truth: the class Javadoc on `NodeGraph`, `BaseNode`, and
`NodeProcessingStatus`. This document is the navigable narrative; when the two
disagree, the code and its Javadoc win — and you should fix this doc.

## Two connection types, two execution styles

| | Data | Flow |
| --- | --- | --- |
| Type | `NodeVariable<T>` on a node, joined by `Edge` | `FlowPort` on a node, joined by `FlowEdge` |
| Carries | a typed value | nothing — only execution order |
| Direction of movement | **pulled**: a node resolves its inputs on demand | **pushed**: a trigger cascades to downstream nodes |
| Entry method | `BaseNode.beginProcessing()` → `NodeGraph.resolve` | `BaseNode.execute()` → `NodeGraph.execute` |

Keeping these separate (rather than folding flow into `NodeVariable`) is
deliberate: flow ports carry no dead value/type machinery, and data ports carry
no control-only special-casing. See the `FlowPort` Javadoc.

## resolve (pull) vs. execute (trigger)

- **`resolve(node)`** — pulls a fresh value through the node's incoming data
  edges, resolving each upstream node first (depth-first), then runs the node's
  `process()`. Every call runs in a fresh `ExecutionContext`, so every node starts
  un-run and a resolve never serves a stale cached value. It blocks the caller until
  done — some callers need the value the instant the call returns.
- **`execute(node)`** — starts a **run**: resolves the node, then cascades along its
  outgoing flow edges to the downstream nodes. Returns immediately; the run executes
  on background virtual threads, concurrently with any other in-flight run.
- **`execute(node, prepare)`** — same, but `prepare` runs inside the run's own
  `ExecutionContext` at the very start. This is how an event source hands its per-event
  payload to exactly one run — the payload lands in that run's value overlay, so a burst
  of events (even overlapping runs) can't clobber one another. Event-source nodes use the
  `BaseNode.execute(Runnable)` protected overload.

## Node status lifecycle & cycle detection

`NodeProcessingStatus`: `NOT_STARTED → IN_PROGRESS → SUCCESS | FAILED`.

- Per-pass state — status, the flow-visited set, activated flow-ports, and computed
  values — lives in a per-pass `ExecutionContext`, not on the shared node/graph objects.
  A fresh context per pass means every node starts `NOT_STARTED` with no explicit reset
  step. (`BaseNode.getStatus()` is a display mirror of the last run's status, for the UI
  and tests; the engine drives execution off the context copy.) This is the foundation for
  the concurrent-runs work — see `docs/design/per-node-execution-policy.md`.
- `IN_PROGRESS` doubles as the **cycle-detection marker**: if resolution reaches a
  node already `IN_PROGRESS` in the same pass, that's a data cycle and the
  engine throws `IllegalStateException` (rather than overflowing the stack).
- A completed node (`SUCCESS`/`FAILED`) is not re-run within the same pass — a
  node reached by two branches runs once. A failed `process()` is caught: the
  node goes `FAILED`, its exception is stored in `getLastError()`, and the pass
  continues.

## Threading model

This is the subtle part. Read the `NodeGraph` class Javadoc alongside this.

- **Concurrent runs on virtual threads.** A *run* is one trigger firing and its
  cascade. Every node firing (and each synchronous `resolve` pull) runs as a task on a
  shared virtual-thread `runExecutor`. Runs execute concurrently and never block one
  another, so a slow node (a camera, an LLM node) only slows its own run. This is safe
  because each run carries an isolated `ExecutionContext` — see the status-lifecycle
  section above and `ExecutionContext`.
- **Fire-and-forget flow.** A node resolves its data synchronously, runs `process()`,
  then *schedules* its activated downstream nodes as independent tasks and does **not**
  wait for them (see the `NodeGraph.Run` inner class). Linear order still holds — a node
  runs downstream only after its own `process()` — but a fan-out no longer joins. The run
  ends when its pending-firing counter reaches zero. Reconvergence therefore has no
  implicit barrier; explicit joining is planned (see the design doc).
- **Per-run resolution lock.** `resolveInternal` synchronizes on a monitor from the run's
  context (`ExecutionContext.lockFor(node)`), not the node object. Within a run this dedups
  a shared data dependency (the second branch blocks, then sees the completed status) and,
  reentrant per thread, turns a data cycle into the `IN_PROGRESS` check rather than a
  deadlock. Being per-run, it does **not** serialize two different concurrent runs that
  share the node — that's what makes `PARALLEL` genuinely parallel.
- **Structural methods stay `synchronized` on the `NodeGraph`** for their brief
  critical section (adding/removing nodes and edges, reading topology), but that
  lock is **never** held for a whole run — so a UI-thread edit isn't forced to
  wait out a slow in-flight trigger.

## Execution policy (re-entrant triggers)

`execute()` is only ever called on **entry-point nodes** — the ones a trigger fires
(a Trigger button, a Repeating Trigger, an event listener, a Discord command). When one
of those is triggered again while a pass it started is still in flight, the node's
`ExecutionPolicy` (a `volatile` field on `BaseNode`, default `QUEUE`) decides what happens:

| Policy | Behavior |
| --- | --- |
| `DROP` | Ignore the new trigger while a pass from this node is running or queued. |
| `RESTART` | Cancel the in-flight pass's remaining cascade (cooperatively — a node already inside `process()` still finishes; only not-yet-reached downstream nodes are skipped) and run a fresh pass with the newest inputs. |
| `QUEUE` (default) | Run after the in-flight run. **Coalesces to the latest**: at most one run is kept pending, so a burst of triggers collapses to a single follow-up carrying the newest inputs, not an unbounded backlog. |
| `PARALLEL` | Start an independent concurrent run every time — no single-flight gate, no coalescing. Safe because each run has an isolated context. |

`DROP`/`QUEUE`/`RESTART` keep a single in-flight run per entry node via `EntryExecution`;
different entry nodes, and `PARALLEL` re-fires, run concurrently. A per-run `PassToken` that
`RESTART` flips and `Run.fire` checks at each node boundary stops a superseded run's remaining
cascade. Because a coalesced follow-up run is started lazily from the run ahead of it (and runs
are fire-and-forget), `awaitIdle()` waits on an `outstandingPasses` count that a run decrements
only once its last firing completes — never on draining a queue.

## The callback-executor seam (why the engine has no JavaFX)

`NodeGraph` dispatches all outward notifications through an injectable
`callbackExecutor` (`setCallbackExecutor`):

- `BaseNode.onExecuted()` and the `GraphExecutionListener` callbacks
  (`onNodeStarted`, `onNodeExecuted`, `onDataEdgeTraversed`, `onFlowEdgeTraversed`).
- The data-edge wiring hooks `onInputEdgeAdded` / `onInputEdgeRemoved`.

Default is `Runnable::run` (fire inline on the calling thread) — what tests use.
The UI supplies `Platform::runLater`, so these callbacks — which touch JavaFX
nodes — run on the FX Application Thread. **If you add any engine → UI
notification, route it through this seam. Do not import JavaFX into `graph/`.**

## `BaseNode` execution-related contract

Hooks the engine calls (all no-ops by default; override as needed):

| Hook | When | Typical use |
| --- | --- | --- |
| `process()` | each pass, after inputs are resolved | the node's actual work |
| `onExecuted()` | right after `process()` (success or fail), via callback executor | push a computed value into a custom UI |
| `onActivated()` | when added to a live graph (incl. on load) | subscribe / register a resource by name (not open a connection) |
| `onRemoved()` | when it leaves a live graph (delete/load/shutdown) | release timers, sockets, threads — must be idempotent |
| `onInputEdgeAdded/Removed(edge)` | after a data edge to this node is (un)wired | grow/shrink dynamic ports (e.g. the object decomposer) |
| `activate(port)` | called *from* `process()` | branch: fire only chosen flow-out port(s). No call = fire all |

Cycle-free data + `activate` are how branch nodes work: see `IfNode` (fires
`True` or `False`) as the canonical example.

## Waiting on async work (tests)

`awaitIdle()` blocks until every accepted run (including a coalesced follow-up) has fully
quiesced — it waits on the `outstandingPasses` count, which a run decrements only once its
last fire-and-forget node firing completes. The app never needs it (nothing waits on
completion — that's the point of running in the background), but tests use it to
deterministically wait for an `execute` run before asserting. Because reconvergence is no
longer implicitly barriered, tests that need a specific order should impose it structurally.
See [testing.md](testing.md).

---

**When you change this, update…** this file (and the `NodeGraph` /
`BaseNode` Javadoc) whenever you change the resolve/execute model, the threading
or locking strategy, the status lifecycle, the callback-executor contract, or the
set of `BaseNode` lifecycle hooks.
