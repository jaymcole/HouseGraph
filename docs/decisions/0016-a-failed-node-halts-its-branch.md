# 0016 — A failed node halts its branch and fires an Error port

## Context

A node whose `process()` threw was marked `FAILED`, had its exception stored, was
logged — and its run **cascaded downstream anyway**, firing every flow-out as
though it had succeeded. Nothing in the graph could observe the failure. On an
unattended daemon that means a camera whose snapshot failed still triggers the
Discord send wired after it, which delivers the previous image, and the only trace
is a log line on a machine nobody is reading.

Nor was it only flow. `resolveInternal` propagated a data edge's value regardless
of whether the node producing it had just failed, so a consumer pulled the
producer's last successful output and could not tell it was stale.

The gap was visible in node code before it was named. `housegraph-github`'s
`GitSyncNode` called `activate(checked)` *before* the work that might throw, with a
comment explaining that it was defending against the engine's "activated nothing →
fire everything" default — a hand-written workaround every node author would have
had to rediscover. Across sixteen node libraries, no node declared a `Failed` or
`Error` flow-out: there was no vocabulary for failure, so nobody expressed one.

## Decision

Failure becomes part of the model, owned by `BaseNode` so every node has it.

Every node carries an engine-owned `Error` flow-out and an `Error Message` output.
A node never activates them; the engine fires them from the failure it recorded.
`FailurePolicy.HALT` — the default — fires that port *instead of* the node's
ordinary flow-outs, discarding whatever it activated before it threw. `CONTINUE`
is the old behaviour, kept for a step whose downstream genuinely does not depend on
it.

A **required** input whose producer failed fails its consumer too, before
`process()` runs, carrying the upstream failure as the cause. An optional input
does not: declaring an input optional is the author saying the node copes without
it.

Cancellation is **not** failure. A run superseded by `RESTART`, or stopped by
`dispose()`, is `FAILED` without being recorded as a fault, and never routes to the
error path — otherwise every coalesced repeating trigger would fire an alert.

The error ports live **outside** `getFlowOutputs()`/`getOutputs()`, reachable
through `getConnectableFlowOutputs()`/`getConnectableOutputs()`. That list is what
edge persistence, edge resolution and the canvas read; everything asking what shape
a node's *author* declared keeps reading the other one.

## Consequences

**Old graphs change behaviour, deliberately.** Save format v5 adds a per-node
`failurePolicy` key whose absence reads as `HALT`, and `migrate` does **not** stamp
`CONTINUE` onto a v4 file. The old behaviour is the bug, and an old graph is the one
most likely to have been quietly suffering from it. A graph that truly wants a
best-effort step sets `CONTINUE` on that node.

Keeping the error ports out of the declared lists was not cosmetic. Folding them in
made every pure data node report as an execution entry point (flow-outs and no
flow-ins is the structural test) and gave every module boundary marker a phantom
port in its module's interface — caught by twenty-four existing tests, which is why
that list stayed clean.

`NodeSignature` is unaffected for free, so adding the error path changed no
installed node type's fingerprint and `housegraph nodes check` kept reporting real
drift rather than all of it at once.

A halted branch can strand a downstream flow join, which waits for every incoming
edge and will now never see one of them. That is the honest answer — the join's
precondition genuinely was not met — but it means a join is a place to think about
where the error path should reconverge.

**Reference:** [`../engine/execution-model.md`](../engine/execution-model.md)
