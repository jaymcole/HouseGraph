# The error path

What a run does when a node fails — and what it deliberately does not do when a node
was merely cancelled. The rest of the execution model is in
[execution-model.md](execution-model.md); this is the failure half of it.

Before this existed, a failed `process()` was caught, logged, and its run cascaded
downstream as though it had succeeded. Nothing in a graph could observe a failure,
which on an unattended machine means a camera whose snapshot failed still triggers
the send wired after it, delivering the previous image. See
[`../decisions/0016-a-failed-node-halts-its-branch.md`](../decisions/0016-a-failed-node-halts-its-branch.md).

Every node carries two ports the engine owns rather than its author: an **`Error`
flow-out** and an **`Error Message`** `String` output. A node never `activate`s
them — by the time they matter it has already thrown. The engine fires them from
the failure it recorded.

`FailurePolicy` decides what a failure does to the cascade, and is per-node, set
from the node's context menu and saved with the graph:

| Policy | A failed node… |
| --- | --- |
| `HALT` (default) | fires **only** its `Error` port — nothing, if that port is unwired |
| `CONTINUE` | fires its ordinary flow-outs as though it had succeeded, and never its `Error` port |

Under `HALT`, ports the node activated **before** it threw are discarded. A node
therefore does not order its `activate` calls defensively around the work that might
fail; `activate(checked)` at the top of a `process()` that throws below fires
nothing.

`Error Message` carries the failure's message — its exception type when the message
is blank, since an empty string downstream reads as "no error" rather than as an
error nobody described. It is written into the run's own context and then committed,
so two concurrent runs failing one node each carry their own message. It is
transient: never saved, and null whenever the last run did not fail.

**Reading it does not fail the reader.** A data edge out of `Error Message` is
exempt from the required-input rule below — that edge exists to carry the failure to
a handler, and failing the handler for reading it would make the error path
unusable.

## A failed data dependency

`resolve` pulls a node's inputs before running it. If the node producing a
**required** input failed, the consumer fails too, before its `process()` runs, with
the upstream failure as the cause — and routes by its own policy, so a chain of
required inputs propagates to the first `Error` port wired anywhere along it.

An **optional** input does not: declaring an input optional is the author saying the
node copes without it.

This is the more common shape in practice. A camera feeding a send over a data edge
is not a flow cascade at all, and without this rule the send would run against the
image the camera produced last time it worked.

## Cancellation is not failure

A run superseded by a `RESTART`, or stopped by `dispose()`, marks its nodes `FAILED`
but is an expected outcome of the engine's own decision, not a fault. It is not
recorded as a failure and never fires an `Error` port — otherwise every coalesced
repeating trigger would fire whatever a graph wires there. A **timeout** is a
fault, and does.

## Where these ports live

They are deliberately **not** in `getFlowOutputs()`/`getOutputs()`, which answer
"what did this node's author declare" — an answer a great deal depends on. A node
with flow-outs and no flow-ins is an execution entry point; a node with no flow
ports is pure data; a module's boundary markers derive a module's whole interface
from theirs. Folding in a port every node has would make every constant a trigger
and give every module a phantom port.

`getConnectableFlowOutputs()` and `getConnectableOutputs()` are the declared ports
plus the error ones, sorted last. Only edge persistence, edge resolution on load and
the canvas read those; everything asking about a node's shape reads the other pair.

**A halted branch can strand a flow join.** A [join](concurrency.md) waits for every
incoming edge, and one of them will now never arrive. That is the honest answer —
its precondition genuinely was not met — but a join is a place to decide where the
error path should reconverge.

---

**When you change this, update…** this file and the `BaseNode` / `NodeGraph` /
`FailurePolicy` Javadoc whenever you change what counts as a failure, what a failure
fires, how a failed data dependency propagates, or where the engine-owned ports live.
