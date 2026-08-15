# Branching, joining and looping

Flow edges define execution order. A node's flow-out ports are anchors control
leaves through; which of them fire, and how often, is what these three patterns
control.

## Branch: `activate(port)`

A plain node adds one unnamed flow-out port and fires it. A branch node adds
several **named** ports and picks between them by calling `activate(port)` from
inside `process()`.

```java
private final FlowPort onTrue  = new FlowPort("True",  FlowPort.Direction.OUT);
private final FlowPort onFalse = new FlowPort("False", FlowPort.Direction.OUT);

@Override public void process(ProcessContext ctx) {
    activate(Boolean.TRUE.equals(ctx.get(condition, false)) ? onTrue : onFalse);
}

@Override public void configureFlowOutputs() {
    addFlowOutput(onTrue);
    addFlowOutput(onFalse);
}
```

**Calling `activate` at all switches the node to selective mode.** A node that
never calls it fires every flow-out port it has. Call it more than once to fire
several.

`graph/nodes/control/IfNode.java` is the canonical example.

## Join: `isFlowJoin()`

A run is fire-and-forget — a node schedules its downstream nodes and does not wait
— so a fan-out never rejoins on its own. **An ordinary node reached by several flow
branches fires on the first arrival**, behaving as an OR/merge.

To wait for *all* branches, override `isFlowJoin()`:

```java
@Override public boolean isFlowJoin() { return true; }
```

The engine counts arrivals per run and fires the node once they reach its wired
incoming-edge count. An unwired port does not count, and a join whose branch was
pruned by an upstream `If` simply does not fire that run.

`graph/nodes/control/JoinNode.java` is the concrete node, with numbered flow-in
ports adjustable from 2 to 8.

## Loop: `runFlowBranchToCompletion(port, seed)`

A node that must fire a flow output **once per item** cannot use `activate()`,
because the cascade fires each downstream node at most once per run.

Instead, call the protected `BaseNode.runFlowBranchToCompletion(port, seed)` from
`process()`, once per item. Each call runs that port's branch as a **fresh isolated
sub-run**, so the per-run flow dedup resets and the body runs afresh. The `seed`
callback sets the loop's per-item output values in the sub-run, and the loop node
is pre-marked complete there, so the body pulls the seeded values without re-running
the loop.

**The call blocks** until the body subtree finishes, so iterations run
**sequentially**.

`graph/nodes/control/ForEachNode.java` is the concrete node: it drives a **Body**
port per element, exposing `Current Item` and `Index`, then `activate`s a
**Completed** port once at the end.

The mechanism is described in [`../engine/loops.md`](../engine/loops.md).

## Choosing between them

| You want | Use |
| --- | --- |
| One of several paths, chosen at runtime | `activate(port)` |
| Continue only after every parallel branch finishes | `isFlowJoin()` |
| Run a branch once per element | `runFlowBranchToCompletion` |
| Run something on every arrival, first-wins | nothing — default behaviour |

---

**When you change this, update…** this file whenever you change `activate`,
the join contract, or the loop seam. The engine side lives in
[`../engine/concurrency.md`](../engine/concurrency.md) and
[`../engine/loops.md`](../engine/loops.md).
