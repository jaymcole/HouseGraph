# Loop bodies

A node that needs to fire one of its flow outputs **more than once** — a for-each
loop running its body per list item — cannot express that through the ordinary
cascade. A downstream node fires at most once per run, because of the `flowVisited`
dedup, and `activate(port)` only selects *which* out-ports fire, not how many
times.

`NodeGraph.runFlowBranchToCompletion(source, sourcePort, seed)` is the primitive.

## How it works

**A fresh run per iteration.** The branch leaving `sourcePort` runs in its own
isolated `ExecutionContext`. Because flow dedup is per-run, a new run per item
resets it, so the body executes afresh every iteration. This reuses the per-run
isolation model rather than working around it.

**The sub-run is seeded.** Before the branch is scheduled, `source` is marked
`SUCCESS` in the sub-context and `seed` writes its per-iteration output values into
that context's overlay. When a body node pulls those outputs it short-circuits on
`source`'s complete status in `resolveInternal` and reads the seeded values. It
does **not** re-run `source`'s `process()`, which would re-enter the loop.

**The call blocks**, so iterations run **sequentially**: item *N+1* starts only
after item *N*'s body subtree finishes. The caller is a run-executor virtual thread
inside its own `process()`, so blocking is cheap. The outer run stays non-idle
throughout — there is no separate `beginPass()` for the sub-run — so `awaitIdle()`
waits for the whole loop.

## Using it

Nodes reach the primitive through the protected
`BaseNode.runFlowBranchToCompletion(port, seed)` seam.

`ForEachNode` in `graph/nodes/control/` is the canonical caller. It loops over its
`List` input, driving its **Body** port once per element with the element and index
seeded onto its `Current Item` and `Index` outputs, then `activate`s its
**Completed** port once at the end.

Authoring guidance is in [`../nodes/flow-control.md`](../nodes/flow-control.md).

---

**When you change this, update…** this file and the `NodeGraph` Javadoc whenever
you change the sub-run seeding, the sequencing guarantee, or the `BaseNode` seam.
