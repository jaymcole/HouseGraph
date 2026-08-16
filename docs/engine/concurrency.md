# Concurrency

The engine runs each trigger as an isolated, concurrent **run** on virtual
threads. A slow node — an LLM call, a camera poll — slows only its own run while
other triggers keep firing.

Read this alongside the `NodeGraph` and `ExecutionContext` Javadoc.

## Isolation: `ExecutionContext`

One context per run. It holds that run's node statuses, `flowVisited` set,
activated flow-out ports, per-node flow-in arrivals, per-node resolution monitors,
join-arrival counts, and the **computed-value overlay**.

A `NodeVariable`'s authored value stays on the node, read-only during a run and
therefore safe to share. Its computed value lives in the context bound to the
current thread. Two runs over the same node never see each other's computed
values.

This is the foundation everything else here depends on: because per-run state is
off the shared objects, runs can overlap without a global lock.

## Fire-and-forget flow

A node resolves its data synchronously, runs `process()`, then **schedules** its
activated downstream nodes as independent tasks and does not wait for them (see
`NodeGraph.Run`).

Linear order still holds — a node runs downstream only after its own `process()` —
but a fan-out no longer joins. The run ends when its pending-firing counter
reaches zero.

**Reconvergence therefore has no implicit barrier.** An ordinary fan-in node fires
on the first branch to arrive, behaving as an OR/merge. To wait for *all* parallel
branches, use a **flow join**: `BaseNode.isFlowJoin()` returns true, and
`JoinNode` is the concrete node. The engine counts arrivals per run and fires the
join once they reach its wired-edge count. An unwired port does not count, and a
join whose branch is pruned by an upstream `If` simply does not fire that run.

The barrier counts **edges, not ports**, which is the one place fan-in into a
single flow-in port changes behaviour: two edges into one join port make the join
wait for both. Everywhere else a node fires once per run regardless, and the port
appears once in `ProcessContext.triggeredVia()` — see
[execution-model.md](execution-model.md).

## Locking

**Per-run resolution lock.** `resolveInternal` synchronizes on a monitor from the
run's context (`ExecutionContext.lockFor(node)`), not on the node object. Within a
run this deduplicates a shared data dependency — the second branch blocks, then
sees the completed status — and, being reentrant per thread, turns a data cycle
into the `IN_PROGRESS` check rather than a deadlock.

Because the monitor is per-run, it does **not** serialize two different concurrent
runs that share the node. That is what makes `PARALLEL` genuinely parallel.

**Structural methods stay `synchronized` on the `NodeGraph`** for their brief
critical section — adding and removing nodes and edges, reading topology — but
that lock is never held for a whole run, so a UI-thread edit is not forced to wait
out a slow in-flight trigger.

## Per-node throughput controls

`runProcess` wraps each `process()` with two optional per-node limits:

- **Concurrency limit** (`BaseNode.getMaxConcurrency()`, 0 = unlimited) — a
  per-node fair `Semaphore`. A run blocks for a permit, so overlapping runs queue
  for an expensive node rather than hammering it.
- **Timeout** (`BaseNode.getTimeoutMillis()`, 0 = none) — a watchdog that
  interrupts an overrun and marks the node `FAILED` with a `TimeoutException`. It
  also trips the node's `ProcessContext` cancellation signal, so a `process()`
  that polls stops even without an interruptible blocking call.

Both are orthogonal to [execution policy](execution-policy.md) and persist through
the save format.

## Waiting on async work

`awaitIdle()` blocks until every accepted run, including a coalesced follow-up,
has fully quiesced. It waits on the `outstandingPasses` count, which a run
decrements only once its last fire-and-forget node firing completes — never on
draining a queue. A firing parked on a mid-cascade `QUEUE` gate keeps its run
non-idle until it runs or is coalesced away.

The app never needs this; nothing waits on completion, which is the point of
running in the background. Tests use it to wait deterministically for a run before
asserting. Because reconvergence is not implicitly barriered, a test that needs a
specific order should impose it structurally. See [testing.md](testing.md).

---

**When you change this, update…** this file and the `NodeGraph` /
`ExecutionContext` Javadoc whenever you change the threading or locking strategy,
the fire-and-forget contract, the join semantics, or the throughput controls.
