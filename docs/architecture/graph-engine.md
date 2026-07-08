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
  `process()`. Every call is a *fresh pass*: all node statuses are reset first, so
  a resolve never serves a stale cached value. It blocks the caller until done —
  some callers need the value the instant the call returns.
- **`execute(node)`** — resolves the node (as above) and then **cascades** along
  its outgoing flow edges, triggering each downstream node in turn. Runs on a
  background thread and returns immediately, so a slow node can't freeze the UI
  thread that triggered it.
- **`execute(node, prepare)`** — same, but `prepare` runs on the execution thread
  at the very start of the pass. This is how an event source hands its per-event
  payload to exactly one pass (captured inside the serialized pass, so a burst of
  events can't clobber one another's values). Event-source nodes use the
  `BaseNode.execute(Runnable)` protected overload.

## Node status lifecycle & cycle detection

`NodeProcessingStatus`: `NOT_STARTED → IN_PROGRESS → SUCCESS | FAILED`.

- Statuses are reset to `NOT_STARTED` at the start of every top-level pass.
- `IN_PROGRESS` doubles as the **cycle-detection marker**: if resolution reaches a
  node already `IN_PROGRESS` on the same thread, that's a data cycle and the
  engine throws `IllegalStateException` (rather than overflowing the stack).
- A completed node (`SUCCESS`/`FAILED`) is not re-run within the same pass — a
  node reached by two branches runs once. A failed `process()` is caught: the
  node goes `FAILED`, its exception is stored in `getLastError()`, and the pass
  continues.

## Threading model

This is the subtle part. Read the `NodeGraph` class Javadoc alongside this.

- **One serialized execution thread** (`executionExecutor`, a single-thread
  daemon named `housegraph-execution`). Every top-level `resolve`/`execute` is
  submitted here, so two separate triggers never run at the same time — the
  second waits in queue. `resolve` submits and blocks; `execute` submits and
  returns.
- **Virtual-thread branch fan-out.** Within one `execute` pass, sibling branches
  of a control-flow fan-out (a node with several outgoing flow edges) run
  concurrently, each on its own virtual thread (`branchExecutor`). A slow branch
  can't hold up an unrelated sibling. The pass doesn't return until every branch
  has joined.
- **Per-node intrinsic lock.** `resolveInternal` synchronizes on the node object
  itself. This gives two things at once: (1) two concurrent branches that share a
  data dependency can't both run that node's `process()` — the second blocks, then
  sees the completed status and returns without re-running; (2) a single thread
  revisiting a node mid-resolution (a data cycle) hits the `IN_PROGRESS` check
  rather than deadlocking (the lock is reentrant per thread).
- **Structural methods stay `synchronized` on the `NodeGraph`** for their brief
  critical section (adding/removing nodes and edges, reading topology), but that
  lock is **never** held for a whole pass — so a UI-thread edit isn't forced to
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
| `QUEUE` (default) | Run after the in-flight pass. **Coalesces to the latest**: at most one pass is kept pending, so a burst of triggers collapses to a single follow-up carrying the newest inputs, not an unbounded backlog. |
| `PARALLEL` | **Not implemented yet** — falls back to `QUEUE`. True concurrent passes need per-pass execution state (see below); the enum/UI/save format carry it now only for forward-compatibility. |

All of this is layered on top of the single serialized execution thread: passes still never
run concurrently. `NodeGraph` tracks per-entry-node state (`EntryExecution`) and a per-pass
`PassToken` that `RESTART` flips and `executeInternal` checks at each node boundary. Because a
coalesced follow-up pass is submitted lazily from inside the pass ahead of it, `awaitIdle()`
tracks an `outstandingPasses` count rather than just draining the executor queue — otherwise
it would return before the coalesced pass ran.

**Why `PARALLEL` needs a refactor.** Pass state lives as mutable fields on the shared
node/graph objects — `BaseNode.status` (reset for *all* nodes at pass start via
`resetAllStatuses()`), `flowVisited`, `activatedOutputs`, and the data values written into each
node's `NodeVariable`. Two concurrent passes over any overlapping node would corrupt each
other. Real parallelism requires extracting that into a per-pass `ExecutionContext`; until then
`PARALLEL` is a no-op alias for `QUEUE`.

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

`awaitIdle()` blocks until every previously-submitted pass has finished. The app
never needs it (nothing waits on completion — that's the point of the background
thread), but tests use it to deterministically wait for an `execute` pass before
asserting. See [testing.md](testing.md).

---

**When you change this, update…** this file (and the `NodeGraph` /
`BaseNode` Javadoc) whenever you change the resolve/execute model, the threading
or locking strategy, the status lifecycle, the callback-executor contract, or the
set of `BaseNode` lifecycle hooks.
