# Node lifecycle and teardown

The hooks the engine calls are listed in
[execution-model.md](execution-model.md#lifecycle-hooks). This document covers the
teardown half, where the ordering and time budgets are load-bearing.

## Teardown is two phases

`onRemoved()` and `releaseResources()` split teardown because the two halves need
opposite threads.

| Hook | Thread | Bounded | For |
| --- | --- | --- | --- |
| `onRemoved()` | the thread that removed the node — the FX thread at shutdown | No | Fast, thread-affine work: stop a `NodeTimer`, clear a running flag, unregister a resource |
| `releaseResources()` | a worker thread | Yes | Slow work that waits on the outside world: kill a child process, withdraw an mDNS registration, log a client out |

Both must be **idempotent**. `releaseResources()` should honour interruption.

The thread split is the reason for the split. Work you intend to bound must not run
on the thread you are standing on, while thread-affine work must run where it
belongs — and `dispose()` runs *on* the FX thread inside `App.stop()`, so
marshalling back to it with `Platform.runLater` would never run.

A node's own teardown does not need the FX thread: it stops a `sdk.NodeTimer`
(immediate, and it never waits on a tick in flight), clears its running flag and
unregisters its name, all of which are plain fields and plain maps. Its controls it
leaves alone — they are going away, and `BaseNode.present(...)` would discard the
update anyway once the view detaches. See
[`../nodes/inline-ui.md`](../nodes/inline-ui.md#your-node-must-work-without-its-ui).

## `dispose()` makes two passes

The fast half runs first, in order, on the calling thread. Then every node's
`releaseResources()` runs **concurrently** on virtual threads under a single
wall-clock deadline: `NodeGraph.DEFAULT_RELEASE_TIMEOUT`, 15 seconds, overridable
with `setReleaseTimeout`.

A node that overruns is interrupted and abandoned. Anything either half throws is
logged and swallowed, so one bad node cannot strand the ones after it.

**Concurrent rather than serial, because a shared shutdown budget cannot work.**
Teardown that waits on the outside world costs seconds per node, so any fixed total
is one added server node away from being too small. Running the releases together
makes shutdown cost the slowest node rather than the sum — a number that does not
grow with the graph.

On an ordinary single-node removal, such as a user deleting a node,
`releaseResources()` is handed to a background thread and not waited for, so
deleting a node never freezes the canvas.

## The timeout chain

The waits nest, and each layer must be strictly longer than the one inside it:

```
node teardown             ~11s   e.g. housegraph-web's NodeProcessServer.stop()
  < NodeGraph release      15s   DEFAULT_RELEASE_TIMEOUT, per node, concurrent
    < App.stop()           25s   SHUTDOWN_TIMEOUT_SECONDS = release + 10
      < Supervisor         40s   STOP_TIMEOUT_SECONDS, before it kills the child JVM
        < launchd         120s   ExitTimeOut, before it kills the daemon itself
```

Invert any pair and the outer wait truncates the inner one, killing a child
part-way through the teardown it was already performing — which orphans exactly the
child processes the chain exists to clean up.

**The supervisor is not the outermost layer; whatever supervises the daemon is.**
The daemon stops its graphs one at a time, so its own teardown can run to 40s *per
graph*, and it is doing that inside a shutdown hook that its supervisor is timing.
launchd's default `ExitTimeOut` is **20 seconds** — shorter than a single graph's
budget — so a LaunchAgent without that key set gets SIGKILLed mid-teardown on every
restart, orphaning the graphs it had not reached. They keep running and keep their
ports, and the replacement graphs then fail to bind. The shipped
[`extras/launchd`](../../extras/launchd) plist sets it to 120; raise it for many
graphs or slow ones. A systemd unit needs `TimeoutStopSec` for the same reason.

Below the chain, one thing no timeout covers: a process a **node** spawned is not
cleaned up by its JVM dying — it is reparented and keeps running. `GraphProcess.stop`
snapshots the child's descendants before signalling it and stops them alongside it,
because a leaked web server holding port 8080 is indistinguishable, from the
replacement graph's point of view, from a port that is simply in use.

## Shutdown paths

JavaFX calls `Application.stop()` when the platform exits, but **not** when the JVM
is signalled — and a signal is how the supervisor restarts a graph.

`App` installs a shutdown hook that calls `Platform.exit()` and waits on a latch
counted down at the end of `stop()`. `GraphProcess.stop` is the other half:
`destroy()` for SIGTERM, wait, then `destroyForcibly()` only if the process will
not go.

Without the hook, a `kill` skips `App.stop()` entirely, `NodeGraph.dispose()` never
runs, no node's `onRemoved()` runs, and connections, child processes and timers are
left to the OS with the tail of the log never reaching disk. This applies to any
ordinary `kill` or logout, not only to supervision. See
[remote-runtime.md](remote-runtime.md).

## Node libraries must opt in

Blocking work left in `onRemoved()` runs unbounded on the shutdown thread. Moving
it to `releaseResources()` is what puts it under the limit. A library built against
an older API keeps working; it just does not get the bound until it adopts the
hook. Authoring guidance is in
[`../nodes/long-lived-resources.md`](../nodes/long-lived-resources.md).

---

**When you change this, update…** this file and the `BaseNode` / `NodeGraph`
Javadoc whenever you change the teardown contract, any timeout in the chain
(including the supervisor's own, outermost one), how descendants are cleaned up, or
the shutdown hook. A change to the timeout chain also touches
[remote-runtime.md](remote-runtime.md).
