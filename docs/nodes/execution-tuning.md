# Execution tuning

Three per-node knobs control what happens when a node is asked to work again while
it is already busy. All three are set by the **user** from the node's right-click
menu, and all three persist in the save file.

As an author you set the defaults and, more importantly, write a `process()` that
behaves correctly under all of them.

## Execution policy

`getExecutionPolicy()` / `setExecutionPolicy()`, default `QUEUE`.

| Policy | What the user gets |
| --- | --- |
| `DROP` | New arrivals ignored while busy |
| `RESTART` | In-flight work cancelled, restarted with the newest inputs |
| `QUEUE` | Runs after the current one, coalescing a burst to a single follow-up |
| `PARALLEL` | An independent concurrent run every time |

The policy applies at two scopes: at an execution entry point it gates a whole
re-triggered run; at a node reached along a flow edge it gates re-entry of that
node's own `process()`. The engine enforces it — the node does nothing. Full
semantics: [`../engine/execution-policy.md`](../engine/execution-policy.md).

**`isExecutionEntryPoint()`** marks a node a trigger fires directly. The default is
"has a flow-out but no flow-in". Override it to `true` if your node self-triggers
*and* has a flow-in — a node whose own button calls `execute()`, for instance.

## Concurrency limit

`setMaxConcurrency(n)`, 0 = unlimited. Caps how many runs may be inside this node's
`process()` at once, through a per-node fair semaphore. A run blocks for a permit,
so overlapping runs queue for an expensive node rather than hammering it.

Set a default for a node that talks to something with its own limits — a rate-limited
API, a single camera, a model that holds a lot of memory.

## Timeout

`setTimeoutMillis(ms)`, 0 = none. Aborts a `process()` that overruns: the engine
interrupts it and marks the node `FAILED` with a `TimeoutException`.

Set a default for anything that can hang on the network.

## Writing a `process()` that cooperates

Two of these — `RESTART` and the timeout — depend on your node noticing. Both trip
the `ProcessContext` cancellation signal.

```java
@Override public void process(ProcessContext ctx) {
    for (Item item : items) {
        ctx.checkCancelled();      // throws CancellationException when superseded
        expensiveWork(item);
    }
}
```

**Poll `ctx.checkCancelled()` periodically in anything that loops or does long CPU
work.** Without it, a CPU-bound `process()` with no interruptible blocking call
cannot be stopped at all, and `RESTART` or a timeout only takes effect at the next
node boundary. `DebugDelayNode` waits in slices and polls between them.

A node that bails on cancellation is marked `FAILED` for that run but **is not
logged as an error** — that is an expected outcome, not a fault.

Blocking calls that respond to interruption need nothing extra. A node that ignores
`ctx` entirely still works, it just cannot be stopped early.

## Choosing defaults

| Node does | Suggested |
| --- | --- |
| Pure calculation | leave everything at default |
| One external call, cheap | default `QUEUE` |
| Expensive call you never want overlapping | `maxConcurrency(1)`, plus a timeout |
| Only the newest input matters (a live preview) | `RESTART`, and poll cancellation |
| Independent per-event work | `PARALLEL` |

Note that `QUEUE` being the default means two `PARALLEL` runs fanning into a shared
downstream node serialize at it unless that node is itself set `PARALLEL`.

---

**When you change this, update…** this file whenever you change the policy
defaults, the entry-point heuristic, or the throughput knobs. The engine side is in
[`../engine/execution-policy.md`](../engine/execution-policy.md) and
[`../engine/concurrency.md`](../engine/concurrency.md).
