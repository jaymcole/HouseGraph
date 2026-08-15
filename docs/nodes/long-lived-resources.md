# Long-lived resources

Some nodes own something long-lived: a bot's gateway connection, a web server, a
camera poller. Two things follow. The object must be reachable from elsewhere on
the graph **without being wired**, and events it produces must drive trigger nodes
that may not exist yet when it starts.

`ResourceRegistry` is the coordination hub for both. The pattern is Node-RED's
config nodes.

## `ResourceRegistry`

One app-wide instance through `ResourceRegistry.shared()`, offering two name-keyed
facilities.

**Object lookup** — `register(name, resource)`, `find(name, type)`,
`unregister(name)`. A resource publishes itself so *action* nodes can fetch it and
call methods. `find` is type-checked and returns `Optional`. `activeNames()` lists
registered names, sorted, for populating a picker.

**Event pub/sub** — `publish(name, payload)`, `subscribe(name, listener)`. A
resource pushes events under its name so *trigger* nodes are driven by them.
`subscribe` returns a `Subscription`; call `cancel()` to stop listening.

Keyed by name rather than by instance, so ordering does not matter — you can
subscribe before the resource exists — and a resource reconnecting does not break
its listeners. The registry is thread-safe, since events may be published from a
resource's own thread while nodes register and subscribe from the FX thread.
Payloads are `Object`, so different resources can carry different event shapes;
subscribers type-check.

## The resource-node contract

**`onActivated()`** — fires when the node joins a live graph, including on load.
Wire up handlers, `register` under the node's name, and `subscribe` if the node
consumes events. **Do not open the connection here.** Being on the canvas is not
the same as being live.

**Liveness is user-driven.** Open the actual connection in response to a user
action — a Connect button in the node's inline UI — typically off the FX thread so
the app stays responsive. See [inline-ui.md](inline-ui.md).

**Renaming** re-keys the registry: `unregister(old)`, then `register(new)`.

**Teardown is two halves**, and getting the split wrong is the most common mistake
here:

| Hook | Thread | Bounded | Put here |
| --- | --- | --- | --- |
| `onRemoved()` | the removing thread — the FX thread at shutdown | No | `unregister`, stop a `Timeline`, reset a control |
| `releaseResources()` | a worker thread | Yes, ~15s per node | Kill a child process, withdraw an mDNS registration, log a client out, close a socket that waits |

Both must be **idempotent**, and both must work even if the node's UI was never
built.

Blocking work left in `onRemoved()` runs unbounded on the shutdown thread. When the
host's budget runs out the JVM exits mid-teardown, orphaning exactly the child
processes the teardown existed to clean up. Moving it to `releaseResources()` puts
it under the limit, running concurrently with every other node's. The mechanism and
the timeout chain are in
[`../engine/node-lifecycle.md`](../engine/node-lifecycle.md).

`releaseResources()` is a concrete hook with a default no-op, so a library that
does not implement it still works — it just does not get the bound.

**Resuming on load.** Liveness being user-driven does not mean it is lost across a
restart. Implement `AutoStartable` to persist whether the node was live and reopen
the connection when the saved graph is reloaded; what is honoured is the user's
earlier Connect, not mere presence on the canvas. See
[state-and-startup.md](state-and-startup.md).

## The other side

Action nodes call `find(name, …)`; trigger nodes call `subscribe(name, …)`.
`EchoResourceNode` in `graph/nodes/resource/` is the minimal in-repo
implementation of the whole pattern — read it alongside this page. `DiscordBotNode`
in the out-of-tree `housegraph-discord` library is the fuller version: it registers
a bot under a chosen name, forwards incoming messages and slash commands into the
registry as events, resolves its token through `sdk.Secrets` so the token is never
wired or saved, and connects and disconnects on user action.

## When not to use the registry

**Where a connection is point-to-point, a plain data edge is clearer**, because the
wire shows the dependency on the canvas. The data-store node hands its
`JsonDocumentStore` to a web-server node over a `Store` output edge rather than
registering by name.

Reach for the registry when a resource is *broadcast* — referenced from many
places, or by trigger nodes that may not exist yet — not merely because it is
long-lived.

## Declaration before connection

A second use of the same idea, worth knowing when several independent nodes need to
contribute to one connected thing.

The Discord library's `SlashCommandRegistry` lets slash-command nodes **declare**
the commands they provide, keyed by bot name, whenever they like. The bot node
reads the full declared set for its name when it connects, and syncs it then.
Declaring does not talk to Discord; connecting does. The natural rule for users:
set up command nodes, then Connect; changing a command afterwards means a reconnect
to apply it.

Reach for this shape whenever several independent things need to register a
declaration ahead of a single node that acts on all of them at once.

---

**When you change this, update…** this file and the `ResourceRegistry` Javadoc
whenever you change the register/find or publish/subscribe semantics, the resource
node lifecycle contract, or the teardown split.
