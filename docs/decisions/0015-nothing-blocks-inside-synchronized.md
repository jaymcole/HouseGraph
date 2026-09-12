# 0015 — Nothing the engine holds while a node blocks is `synchronized`

## Context

Runs execute on a virtual-thread-per-task executor, and two of the engine's locks
are held while a node may block: the per-run resolution lock, which spans the whole
of `process()`, and the mid-cascade re-entry gate a `QUEUE` arrival parks on. Both
were intrinsic monitors — `synchronized` plus `wait()`/`notifyAll()` — and the gate's
own Javadoc described a waiting arrival as parking "a cheap virtual thread".

It is not cheap. On Java 21 a virtual thread that blocks inside a `synchronized`
block **pins its carrier** rather than unmounting, and the scheduler runs one
carrier per CPU and does not compensate for a pinned one. The core count was
therefore a hard cap on how many nodes could block simultaneously, and a graph
needing one more made no progress until something timed out.

It surfaced as a test that passed on every 4-core machine and failed on the 3-core
macOS CI runner: two concurrent invocations of one module need four blocked units —
two module nodes waiting on their interiors, two interior nodes meeting at a
barrier. The failure looked like a module harvesting a null output, which is what a
node that timed out leaves behind, so it read as a value-isolation bug rather than
as scheduler starvation. Every release since that test landed was blocked by it.

## Decision

Both become a `ReentrantLock`, the gate's wait a `Condition`. Neither may go back:
`java.util.concurrent` locks park through `LockSupport`, which unmounts a virtual
thread, so a blocked node costs a virtual thread and not a core.

Structural `synchronized` methods on `NodeGraph` stay as they are — nothing blocks
inside one.

## Consequences

The number of nodes that may block at once is bounded by memory rather than by
cores, which is what the virtual-thread design was for. Semantics are unchanged:
`ReentrantLock` is reentrant per thread exactly as a monitor is, so the data-cycle
`IN_PROGRESS` check and the gate's hand-off behave as before.

The tempting simplification — "this is just a mutex, make it `synchronized`" — is
now a correctness regression on any machine with few cores, and one that shows up as
a wrong value rather than as a hang. `NodeGraphTest.moreNodesCanBlockAtOnceThanTheMachineHasCores`
is sized past the core count so it fails everywhere rather than only on a small
runner.

JDK 24's JEP 491 removes the pinning, at which point this record becomes history
rather than a rule — but not before the project's Java 21 baseline moves.

**Reference:** [`../engine/concurrency.md`](../engine/concurrency.md)
