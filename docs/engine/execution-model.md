# Execution model

`NodeGraph` owns a set of `BaseNode`s and the connections between them, and drives
their execution. Two connection types carry two different things, and are moved in
opposite directions.

| | Data | Flow |
| --- | --- | --- |
| Type | `NodeVariable<T>`, joined by `Edge` | `FlowPort`, joined by `FlowEdge` |
| Carries | a typed value | nothing — only execution order |
| Direction | **pulled**: a node resolves its inputs on demand | **pushed**: a trigger cascades downstream |
| Entry | `BaseNode.beginProcessing()` → `NodeGraph.resolve` | `BaseNode.execute()` → `NodeGraph.execute` |

Keeping them separate means flow ports carry no value or type machinery, and data
ports carry no control-only special cases.

**Cardinality differs, for that reason.** A data input is fed by **at most one**
`Edge` — its value has to be pulled from one unambiguous source, so
`NodeGraph.attachEdge` throws on a second. A flow-in port is fed by **any number**
of `FlowEdge`s, because it carries no value and so has nothing to disambiguate: two
triggers wired into one `Start` port both trigger it. `registerFlowEdge` has no
cardinality gate.

## resolve and execute

- **`resolve(node)`** pulls a fresh value through the node's incoming data edges,
  resolving each upstream node depth-first, then runs the node's `process()`.
  Every call runs in a fresh `ExecutionContext`, so no resolve ever serves a stale
  cached value. It blocks the caller, because callers need the value the instant
  the call returns.
- **`execute(node)`** starts a **run**: it resolves the node, then cascades along
  its outgoing flow edges. It returns immediately; the run proceeds on background
  virtual threads, concurrently with any other in-flight run.
- **`execute(node, prepare)`** is the same, with `prepare` running inside the run's
  own `ExecutionContext` at the very start. This is how an event source hands a
  per-event payload to exactly one run: the payload lands in that run's value
  overlay, so overlapping runs cannot clobber one another. Event-source nodes
  reach it through the protected `BaseNode.execute(Runnable)` overload.

A **run** is one trigger firing and everything that cascades from it. Runs are the
unit of isolation throughout the engine — see [concurrency.md](concurrency.md).

## Driving another graph's run

A node whose work *is* another graph — a module node running the graph it references —
starts a run on that second `NodeGraph` and blocks until **that run** settles.
`NodeGraph.runToCompletion(entry, seed, harvest, cancelled)` is the primitive, and
`runToCompletion(seed, harvest, cancelled)` is its data-only shape, which seeds a context
and fires nothing.

Neither `execute` nor `awaitIdle()` could serve. `execute` returns immediately, and a
driving `process()` cannot: `Run.fire` reads the driver's `activate`d flow-out ports the
moment `process()` returns, so a port chosen after that selects nothing.
`awaitIdle()` waits for the whole graph, which never settles if it holds a repeating
trigger.

Three things cross the boundary, and each is wrong by default rather than merely absent:

- **Values in**, through `seed`, which runs in the driven run's own `ExecutionContext`
  before the entry fires — the same mechanism as `execute(node, prepare)`, for the same
  reason. It runs on the driven graph's thread, so it must *carry* the values it writes:
  the driving run's overlay is not visible from inside.
- **Values out**, through `harvest`, which runs once the run has quiesced and **still
  inside its context**, holding a `RunScope`. That is the only moment the run's computed
  values can be read as its own; afterwards a node's ports hold the committed mirror,
  which is last-run-wins across concurrent runs. `RunScope.pull` resolves a node within
  the run (short-circuiting on anything it already ran), and `RunScope.hasRun` says
  whether control reached a node — per run, so two overlapping runs never see each
  other's answer.
- **Cancellation**, which is OR-ed into the driven run: a driver superseded by a
  `RESTART`, timed out, or interrupted stops the run it drives. Nodes inside see it
  through their `ProcessContext`, and the cascade stops at the next node boundary. The
  wait polls and then throws `CancellationException` rather than waiting the run out — a
  cancelled driver must return promptly, and an interior node that ignores cancellation
  would otherwise hold it.

Two things do **not** cross by themselves, and a driver hands them on through
`BaseNode.getOwningGraph()`: the [callback executor](#the-callback-executor-seam), which a
fresh graph defaults to `Runnable::run`, and the [step delay](#watching-a-run), which is
per-graph and would leave a watched run opaque the moment it entered the second graph.

The driven graph's own `ExecutionPolicy` at the entry node is **not** consulted — the run
is built directly, as `runFlowBranchToCompletion`'s is, because coalescing a driven run
would leave its driver blocked on a run that never starts. Nodes *inside* it are gated by
their own policies as usual. What that means for the driving node is in
[execution-policy.md](execution-policy.md).

## Node status

`NodeProcessingStatus`: `NOT_STARTED → IN_PROGRESS → SUCCESS | FAILED`.

Per-run state — status, the flow-visited set, activated flow ports, and computed
values — lives in a per-run `ExecutionContext`, not on the shared node or graph
objects. A fresh context per run means every node starts `NOT_STARTED` with no
explicit reset step. `BaseNode.getStatus()` mirrors the last run's status for the
UI and for tests; the engine drives execution off the context copy.

- `IN_PROGRESS` doubles as the **cycle-detection marker**. If resolution reaches a
  node already `IN_PROGRESS` in the same run, that is a data cycle, and the engine
  throws `IllegalStateException` rather than overflowing the stack.
- A completed node is not re-run within the same run, so a node reached by two
  branches runs once.
- A failed `process()` is caught: the node goes `FAILED`, its exception is stored
  in `getLastError()`, and the run continues.

## Which flow ports control came in and went out by

A node's flow ports are named, and the engine tracks both ends of an arrival. Both
halves live in the run's `ExecutionContext`, so concurrent runs over one node never
see each other's.

**Out — the node chooses.** `BaseNode.activate(port)`, called from `process()`,
records a flow-out port on the context (`ExecutionContext.activate`). When
`Run.fire` cascades, it reads them back (`activatedOf`) and follows only those
ports' edges. Activating *nothing* (never calling `activate`) fires *all*
out-ports, so an ordinary node needs no activation call. This is how `IfNode`
prunes a branch.

`BaseNode.activateNone()` is the other end of that default: it records the node as
having explicitly fired *no* out-port this run (`ExecutionContext.activateNone` /
`activatesNone`), for a firing that must not cascade at all. This is distinct from
never calling `activate` — that still means "fire all" — so a node needs
`activateNone()` when one of its IN ports arms/disarms it without meaning "now also
fire out" (see below). `TriggerRepeatingNode`'s Start/Stop ports use it: they must
not fire the node's own periodic trigger port.

**In — the engine tells the node.** `Run.schedule` records the IN port each
arriving `FlowEdge` targeted (`ExecutionContext.recordFlowArrival`), and the node
reads them back through `ProcessContext.triggeredVia()` /
`wasTriggeredVia(port)`. This is what lets one node expose several named entry
points that mean different things — a `Start` port and a `Stop` port on one
resource node, rather than two nodes or a mode input.

`triggeredVia()` is **empty** whenever no flow edge was involved:
`beginProcessing()`'s pull, a node resolved as another node's data dependency, and
the node a run was triggered on directly (its trigger came from outside the graph).
A node with one flow-in port that does one thing can ignore it entirely; nothing
about single-port nodes changed.

**Recording an arrival is separate from acting on it.** The dedup is untouched: a
node still fires **once per run**, whether one port is fed by several edges
(fan-in) or several ports are each fed by one. A port appears once in
`triggeredVia()` however many of its edges arrive. Several ports appear only when
they genuinely arrived together — a [flow join](concurrency.md), which fires only
after every incoming edge has arrived, or concurrent sibling branches reaching two
ports before the node ran.

The consequence worth knowing: **two ports on one node are not two behaviours
within a single run.** Only the arrivals recorded by the time the single firing
starts are visible; a later arrival at another port is deduped away and the node
never learns of it. Distinct behaviours belong to distinct triggers, and so to
distinct runs, where each firing sees exactly its own port. That is the shape the
feature is for.

## `ProcessContext`

`process(ProcessContext ctx)` receives a fresh context the engine builds for that
one call, in `NodeGraph.runProcess`. It carries two things.

**Cooperative cancellation** — `ctx.isCancelled()` and `ctx.checkCancelled()`
(which throws `CancellationException`). A run's cancellation sources are OR-ed
into the signal the context reads:

- a superseding `RESTART`, through the run's `PassToken`,
- an elapsed per-node timeout,
- thread interruption.

This is the only access path to that signal. Without it the engine could check
cancellation only *between* nodes, so a CPU-bound `process()` with no interruptible
blocking call could not be stopped at all. A node that loops or does long work
should poll `ctx.checkCancelled()` periodically; `DebugDelayNode` waits in slices
and polls between them. A node that ignores `ctx` runs to completion.

**Null-safe value access** — `ctx.get(input, fallback)` returns the input's value
or a fallback when null, plus `ctx.get(var)` and `ctx.set(var, value)` as
overlay-aware pass-throughs.

**The flow-in ports that triggered this firing** — `ctx.triggeredVia()` and
`ctx.wasTriggeredVia(port)`, snapshotted when the context is built so the set
cannot grow under a `process()` mid-call. See the section above.

A node that bails on cancellation — returning early, or letting `checkCancelled()`
throw — is marked `FAILED` for that run but is **not** logged as an error, since
that is an expected outcome rather than a fault. `ProcessContext.uncancelled()` is
the factory for invoking `process()` directly outside the engine, which unit tests
use.

## The callback-executor seam

`NodeGraph` dispatches every outward notification through an injectable
`callbackExecutor` (`setCallbackExecutor`):

- `BaseNode.onExecuted()` and the `GraphExecutionListener` callbacks
  (`onNodeStarted`, `onNodeExecuted`, `onDataEdgeTraversed`, `onFlowEdgeTraversed`),
- the data-edge wiring hooks `onInputEdgeAdded` / `onInputEdgeRemoved` on the edge's
  target and `onOutputEdgeAdded` / `onOutputEdgeRemoved` on its source, each
  dispatched as its own task so one throwing does not suppress the other.

The default is `Runnable::run`, firing inline on the calling thread, which is what
tests use. The app supplies `Platform::runLater`, so callbacks that touch JavaFX
nodes run on the FX Application Thread.

**Any new engine-to-UI notification goes through this seam.** Do not import JavaFX
into `graph/`.

## Watching a run

`NodeGraph.setStepDelayMillis` pauses before each node's `process()` in a flow-driven
run. A real graph finishes faster than the eye follows, so the callbacks above fire
and clear in one frame; spacing the firings out is what makes them watchable. Zero —
the default — runs at full speed.

The pause sits between the `onNodeStarted` notification and `runProcess`. Before it,
the node is already lit, so it stays lit for the wait. After it come the node's
concurrency permit and its timeout watchdog, so the delay neither holds a permit it
isn't using nor spends a node's timeout budget waiting — a node with a 2-second
timeout doesn't start failing because the delay was set to 3. It waits in slices and
polls cancellation between them, so a superseding `RESTART` or a `dispose()` drops
the remainder instead of waiting it out.

**The synchronous `resolve` path is exempt**, because it blocks its caller and that
caller may be the FX application thread — an inline-UI button calling
`beginProcessing()`, as `TriggerRepeatingNode`'s Start button does. Pausing there
would freeze the UI rather than animate it. The run's `ExecutionContext` carries the
distinction, so a branch started by `runFlowBranchToCompletion` inherits it from the
run driving it rather than assuming either answer.

That inheritance has a third case, because `runFlowBranchToCompletion` is not only a
loop primitive: an **event source that answers its caller** — a Discord slash command
or button click, a webhook — drives its own flow output from the thread the event
arrived on and waits for the branch. There is no enclosing context to inherit from,
and nothing above it is blocked on the delay, so such a branch **steps**. A run is
watched whenever it is flow-driven, whether an event source reached it through
`execute(node, prepare)` or drove a branch itself.

**It changes timing, and so changes behaviour that depends on timing.** Firings that
used to overlap now queue or are shed by their node's [execution
policy](execution-policy.md), and a loop body pays the delay per iteration. It is a
debugging aid, not a throttle — `BaseNode.getMaxConcurrency()` is the throttle. For
the same reason it is session-scoped and absent from the
[save format](save-format.md): a graph carries no delay, so a headless deployment of
it always runs at full speed.

## Lifecycle hooks

The methods the engine calls on a node, all no-ops by default:

| Hook | When | Typical use |
| --- | --- | --- |
| `process(ProcessContext)` | each run, after inputs resolve | the node's work |
| `onExecuted()` | right after `process()`, via the callback executor | push a computed value into inline UI |
| `onActivated()` | when added to a live graph, including on load | register or subscribe by name |
| `onRemoved()` | when it leaves a live graph, on the removing thread | fast, thread-affine teardown |
| `releaseResources()` | after `onRemoved()`, on a worker, time-bounded | slow teardown |
| `onInputEdgeAdded/Removed(edge)` | after a data edge into the node is (un)wired | grow or shrink dynamic ports |
| `onOutputEdgeAdded/Removed(edge)` | after a data edge out of the node is (un)wired | react to whether an output is consumed |
| `activate(port)` | from within `process()` | branch: fire only the chosen flow-out ports |
| `activateNone()` | from within `process()` | arm/disarm: fire no flow-out port at all this run |
| `ctx.triggeredVia()` | read from within `process()` | tell apart which flow-in port fired this node |
| `runFlowBranchToCompletion(port, seed)` | from within `process()` | loop: run one branch per item |
| `getOwningGraph()` | from a node driving a second graph | pass on the callback executor and step delay |

Two more are the node's own, not the engine's: `present(Runnable)` runs a block
against the node's inline controls and discards it when nothing is drawing that
node, and `hasView()` reports whether anything is. They are what keep a node's
running lifecycle independent of whether it was ever rendered — see
[`../nodes/inline-ui.md`](../nodes/inline-ui.md#your-node-must-work-without-its-ui).

Full detail on the teardown pair is in
[node-lifecycle.md](node-lifecycle.md).

---

**When you change this, update…** this file and the `NodeGraph` / `BaseNode` /
`ProcessContext` Javadoc whenever you change the resolve/execute model, the status
lifecycle, the callback-executor contract, or the set of lifecycle hooks.
