# Branching, joining and looping

Flow edges define execution order. A node's flow ports are the anchors control
enters and leaves through; which of them fire, how often, and which one brought
control in is what these patterns control.

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

## Several entry points: `ctx.triggeredVia(...)`

The mirror of `activate`. A node adds several **named** flow-in ports and asks the
context which one control arrived through:

```java
private final FlowPort start = new FlowPort("Start", FlowPort.Direction.IN);
private final FlowPort stop  = new FlowPort("Stop",  FlowPort.Direction.IN);

@Override public void process(ProcessContext ctx) {
    if (ctx.wasTriggeredVia(stop)) {
        shutDown();
    } else {
        startUp();
    }
}

@Override public void configureFlowInputs() {
    addFlowInput(start);
    addFlowInput(stop);
}
```

`ctx.triggeredVia()` is the whole set; `ctx.wasTriggeredVia(port)` is the usual
one-port check. Both read **empty** when no flow edge was involved — a
`beginProcessing()` pull, a resolve as someone's data dependency, or the node a run
was triggered on — so give the branch a sensible default rather than assuming a
port is always present. A node with one flow-in port needs none of this.

**One port per trigger, not two ports per run.** A node fires at most once per run,
so wiring both ports from the same trigger does not run both branches: the first
arrival fires the node and the second is deduped away. Start and Stop are different
events, so they come from different triggers and different runs, and each firing
sees exactly its own port.

**A flow-in port takes any number of edges.** Two triggers can both feed one
`Start` port and either fires it — unlike a data input, which accepts one edge. If
the node is also a join (below), note that the barrier counts edges, not ports.

**If the node also has a flow-out, arriving via one of these entry points still
fires it by default** — `activate`'s "never call it, fire everything" default
applies here too. That's wrong for an arm/disarm pair like Start/Stop on
`TriggerRepeatingNode`: the timer's own periodic tick should fire the flow-out, not
the Start or Stop signal that armed or disarmed it. Call `activateNone()` for those
firings to suppress the cascade entirely:

```java
@Override public void process(ProcessContext ctx) {
    if (ctx.wasTriggeredVia(start) || ctx.wasTriggeredVia(stop)) {
        activateNone();      // arm/disarm only - never cascades
    }
    // ... a periodic self-triggered execute() call takes neither branch here,
    // so it falls through to the ordinary "fire everything" default.
}
```

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
| Different work depending on which entry point fired | `ctx.wasTriggeredVia(port)` |
| Suppress this firing's cascade entirely | `activateNone()` |
| Continue only after every parallel branch finishes | `isFlowJoin()` |
| Run a branch once per element | `runFlowBranchToCompletion` |
| Run something on every arrival, first-wins | nothing — default behaviour |

---

**When you change this, update…** this file whenever you change `activate`,
the flow-in arrival contract, the join contract, or the loop seam. The engine side
lives in
[`../engine/execution-model.md`](../engine/execution-model.md),
[`../engine/concurrency.md`](../engine/concurrency.md) and
[`../engine/loops.md`](../engine/loops.md).
