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

## Data edge type compatibility & hidden converters

A data anchor's type is a `Class<?>` on the `NodeVariable`. An `Edge` may join an
output to an input when the input type is **assignable from** the output type
(exact match, a subtype, or an `Object` input that accepts anything) **or** a
converter is registered for the `(output, input)` type pair in `TypeConverters`.
`NodeGraph.attachEdge` is the authoritative gate (it throws on an incompatible
pair); the UI's `GraphCanvas.isValidConnection` mirrors it for the drag-time
highlight. Both call `TypeConverters.isCompatible(from, to)`.

`TypeConverters` (in `graph/`, so it stays headless) ships a built-in matrix that
interconverts `Integer`, `Double`, `Float`, and `Boolean` in both directions.
Additional converters can be registered on the fly via
`TypeConverters.register(from, to, safety, fn)` — the extension point for plugins.
When a value propagates (`NodeGraph.propagateValue`), the registered converter
coerces it at the handoff; when no converter applies the raw value is passed
through unchanged, preserving legacy raw-handoff paths. These hidden converters are
distinct from the explicit converter **nodes** in `graph.nodes.converters` (which
stay for visible, first-class conversions and for targets the matrix does not
cover, e.g. `*` → `String`).

**Conversion safety.** Every converter carries a `ConversionSafety` level, and
`TypeConverters.classify(from, to)` reports it for a pair — `SAFE` (assignable, or
a lossless/widening converter: `Integer` → `Float`/`Double`, `Boolean` → number),
`CAUTIOUS` (predictable loss: `Double`/`Float` → `Integer` truncation, `Double` →
`Float` precision), `RISKY` (drastic loss: number → `Boolean` collapses non-zero to
`true`), or `INCOMPATIBLE` (no path). The level is advisory for connecting — both
gates allow anything that is not `INCOMPATIBLE` (`isCompatible` ≡ `classify != INCOMPATIBLE`).
Its purpose is UX: the canvas colours a candidate anchor green / yellow / orange /
red from `classify` while an edge is dragged (see [ui.md](ui.md)).

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
  implicit barrier: an ordinary fan-in node fires on the first branch to arrive (OR/merge),
  and to wait for **all** parallel branches you use a **flow join** (`BaseNode.isFlowJoin()`,
  concrete `JoinNode`) — an AND-barrier that fires once every wired incoming edge has arrived.
- **Per-run resolution lock.** `resolveInternal` synchronizes on a monitor from the run's
  context (`ExecutionContext.lockFor(node)`), not the node object. Within a run this dedups
  a shared data dependency (the second branch blocks, then sees the completed status) and,
  reentrant per thread, turns a data cycle into the `IN_PROGRESS` check rather than a
  deadlock. Being per-run, it does **not** serialize two different concurrent runs that
  share the node — that's what makes `PARALLEL` genuinely parallel.
- **Per-node throughput controls.** `runProcess` wraps each `process()` with the node's
  optional concurrency limit (a per-node fair `Semaphore` — a run blocks for a permit, so
  overlapping runs queue for an expensive node rather than hammer it) and timeout (a watchdog
  that interrupts an overrun and marks the node `FAILED`). See `BaseNode.getMaxConcurrency()` /
  `getTimeoutMillis()`. The timeout also trips the node's `ProcessContext` cancellation signal, so a
  `process()` that polls it stops even without an interruptible blocking call (see
  [ProcessContext](#processcontext--the-per-invocation-handle)).
- **Structural methods stay `synchronized` on the `NodeGraph`** for their brief
  critical section (adding/removing nodes and edges, reading topology), but that
  lock is **never** held for a whole run — so a UI-thread edit isn't forced to
  wait out a slow in-flight trigger.

## Execution policy (re-entrant triggers)

Every node carries an `ExecutionPolicy` (a `volatile` field on `BaseNode`, default `QUEUE`). The
same four values are enforced at **two scopes** — the difference is what "in flight" means and what
gets dropped/queued/restarted. Full design: [`docs/design/per-node-execution-policy.md`](../design/per-node-execution-policy.md).

| Policy | Behavior |
| --- | --- |
| `DROP` | Ignore the new arrival while one is running or queued. |
| `RESTART` | Cancel the in-flight work and run fresh with the newest inputs. Cooperative: the run is flagged cancelled (surfaced to the node through its `ProcessContext`, and, mid-cascade, by interrupting the holder), but a node already inside `process()` that never checks stops only at the next node boundary. |
| `QUEUE` (default) | Run after the in-flight one. **Coalesces to the latest**: at most one is kept pending, so a burst collapses to a single follow-up, not an unbounded backlog. |
| `PARALLEL` | Start an independent concurrent run every time — no single-flight gate, no coalescing. Safe because each run has an isolated context. |

**Entry-node scope (whole run).** `execute()` is called on an **entry-point node** — the one a
trigger fires (a Trigger button, a Repeating Trigger, an event listener, a Discord command). When
one is triggered again while a run it started is still in flight, its policy gates the whole run.
`DROP`/`QUEUE`/`RESTART` keep a single in-flight run per entry node via `EntryExecution`; different
entry nodes, and `PARALLEL` re-fires, run concurrently. A per-run `PassToken` that `RESTART` flips
and `Run.fire` checks at each node boundary stops a superseded run's remaining cascade.

**Mid-cascade scope (one node's `process()`).** A node reached along a flow edge during a run
re-applies its policy at a narrower grain via a `ReentryGate` (keyed by node in `NodeGraph`): the
gate is held only while some run is inside that node's `process()`. If a **second** run's flow
reaches the node while the gate is held, its policy decides — `DROP` abandons that branch, `QUEUE`
parks it as a single coalesced waiter (a newer arrival evicts an older one) and hands it the node
when the holder's `process()` returns, `RESTART` additionally interrupts the holder, `PARALLEL`
never gates. The gate is released the instant `process()` returns — before downstream is scheduled
— so the window is exactly that node's own `process()`. This is what lets one trigger fan out into
branches with different re-entrancy behavior (a slow branch that `DROP`s overlaps while a fast
sibling `QUEUE`s). A node still fires at most once per run (`flowVisited`); the gate is only about
*different* runs overlapping on it.

**How the two compose.** The entry policy decides whether concurrent runs start at all, so a
mid-cascade gate only ever sees overlap when the entry is `PARALLEL` (or when distinct entry nodes
feed a shared node). One consequence of the default: two `PARALLEL` runs that fan into a shared
downstream node now **serialize** at it unless that node is itself set `PARALLEL` (its default
`QUEUE` makes it process one run at a time).

Because a coalesced follow-up run is started lazily from the run ahead of it (and runs are
fire-and-forget), `awaitIdle()` waits on an `outstandingPasses` count that a run decrements only
once its last firing completes — never on draining a queue. A firing parked on a mid-cascade
`QUEUE` gate keeps its run non-idle until it runs or is coalesced away.

## Loop bodies (seeded sub-runs)

A node that needs to fire one of its flow outputs **more than once** — a for-each
loop running its body once per list item — can't express that through the
ordinary cascade: a downstream node is fired at most once per run (`flowVisited`
dedup), and `activate(port)` only *selects* which out-ports fire, not how many
times. `NodeGraph.runFlowBranchToCompletion(source, sourcePort, seed)` is the
primitive for this:

- It runs the branch leaving `sourcePort` in a **fresh, isolated run** (its own
  `ExecutionContext`). Because dedup is per-run, a new run per item resets it, so
  the body executes afresh every iteration — this reuses the per-run isolation
  model rather than fighting it.
- Before scheduling the branch, the sub-run is **seeded**: `source` is marked
  `SUCCESS` in the sub-context and `seed` sets its per-iteration output values in
  that context's overlay. So when a body node pulls those outputs it
  short-circuits on `source`'s complete status in `resolveInternal` and reads the
  seeded values — it does **not** re-run `source`'s `process()` (which would
  re-enter the loop).
- The call **blocks** until the sub-run quiesces, so iterations run **sequentially**
  (item *N+1* starts only after item *N*'s body subtree finishes). The caller is a
  run-executor virtual thread inside its own `process()`, so blocking is cheap; its
  outer run stays non-idle throughout (no separate `beginPass()` for the sub-run),
  so `awaitIdle()` waits for the whole loop.

Nodes reach this via the protected `BaseNode.runFlowBranchToCompletion(port, seed)`
seam. `ForEachNode` (in `graph/nodes/control/`) is the canonical user: it loops
over its `List` input, driving its **Body** port once per element with the element
and index seeded onto its `Current Item` / `Index` outputs, then `activate`s its
**Completed** port once at the end.

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
| `process(ProcessContext ctx)` | each pass, after inputs are resolved | the node's actual work (see [ProcessContext](#processcontext--the-per-invocation-handle)) |
| `onExecuted()` | right after `process()` (success or fail), via callback executor | push a computed value into a custom UI |
| `onActivated()` | when added to a live graph (incl. on load) | subscribe / register a resource by name (not open a connection) |
| `onRemoved()` | when it leaves a live graph (delete/load/shutdown) | release timers, sockets, threads — must be idempotent |
| `onInputEdgeAdded/Removed(edge)` | after a data edge to this node is (un)wired | grow/shrink dynamic ports (e.g. the object decomposer) |
| `activate(port)` | called *from* `process()` | branch: fire only chosen flow-out port(s). No call = fire all |
| `runFlowBranchToCompletion(port, seed)` | called *from* `process()` | loop: run one flow-out branch once per item as a seeded sub-run (see [Loop bodies](#loop-bodies-seeded-sub-runs)) |

Cycle-free data + `activate` are how branch nodes work: see `IfNode` (fires
`True` or `False`) as the canonical example; `ForEachNode` uses
`runFlowBranchToCompletion` to run its body once per list item.

## `ProcessContext` — the per-invocation handle

`process(ProcessContext ctx)` receives a fresh `ProcessContext` the engine builds for that one
call (in `NodeGraph.runProcess`). It carries two things:

- **Cooperative cancellation** — `ctx.isCancelled()` / `ctx.checkCancelled()` (throws
  `CancellationException`). This is the part with no other access path. A run's cancellation
  sources — a superseding `RESTART` (via the run's `PassToken`, wired onto the run's
  `ExecutionContext`), an elapsed per-node **timeout**, or thread interruption — are OR-ed into the
  signal the context reads. Before the context existed, the engine only checked cancellation
  *between* nodes, so a CPU-bound `process()` (an image analysis, a long loop) with no interruptible
  blocking call **could not be stopped at all**. A node that loops or does long work should poll
  `ctx.checkCancelled()` periodically; `DebugDelayNode` is the canonical example (it waits in slices
  and polls between them). A node that ignores `ctx` runs to completion exactly as before.
- **Null-safe value access** — `ctx.get(input, fallback)` returns the input's value or a fallback
  when null (replacing the per-node `getSafeValue` helpers), plus `ctx.get(var)` / `ctx.set(var,
  value)` as thin pass-throughs to the same overlay-aware `NodeVariable` accessors.

A node that cooperatively bails on cancellation (returns early, or lets `checkCancelled()` throw) is
marked `FAILED` for that run but **not** logged as an error — it's an expected outcome, distinct from
a `process()` that threw for a real reason. `ProcessContext.uncancelled()` is the public factory for
invoking a node's `process()` directly outside the engine (there is nothing to cancel), used by unit
tests.

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
or locking strategy, the status lifecycle, the callback-executor contract, the
data-edge type-compatibility / converter model (`TypeConverters`), or the
set of `BaseNode` lifecycle hooks.
