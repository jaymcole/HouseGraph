# Resources & Event Pub/Sub

Some nodes own something long-lived — a Discord gateway connection, an echo
channel, a camera poller. These "resources" must be reachable from anywhere on
the graph *without being wired*, and events they produce must drive trigger nodes
that may not even exist yet when the resource starts. `ResourceRegistry` is the
coordination hub that makes this work (the pattern is Node-RED's config nodes).

## `ResourceRegistry` — two lookups, both keyed by name

A single app-wide instance via `ResourceRegistry.shared()`. It offers two
complementary, name-keyed facilities:

1. **Object lookup** — `register(name, resource)` / `find(name, type)` /
   `unregister(name)`. A resource publishes itself so *action* nodes can fetch it
   and call methods (e.g. a send-message node finding its bot). `find` is
   type-checked and returns `Optional`. `activeNames()` lists registered names
   (sorted) for populating a picker.
2. **Event pub/sub** — `publish(name, payload)` / `subscribe(name, listener)`.
   A resource pushes events under its name so *trigger* nodes are driven by them.
   `subscribe` returns a `Subscription` handle; call `cancel()` to stop
   listening.

**Why keyed by name, not instance:** order doesn't matter (you can subscribe
before the resource exists), and a resource reconnecting doesn't break listeners.
The registry is thread-safe (`ConcurrentHashMap` + `CopyOnWriteArrayList`), since
events may be published from a resource's own thread while nodes register /
subscribe from the UI thread. Payloads are `Object` so different resources can
carry different event shapes (`String`, `DiscordMessage`, …); subscribers
type-check.

## The resource-node pattern

A resource node is a `BaseNode` that owns a long-lived object. The contract:

- **`onActivated()`** (fires when the node joins a live graph, including on load):
  wire up handlers and `register` under the node's name, and `subscribe` if it
  consumes events. **Do not open the connection here** — being on the canvas is
  not the same as being live.
- **Liveness is user-driven.** Opening the actual connection happens in response
  to a user action (a Connect button in the node's `NodeContentProvider` UI),
  typically off the UI thread so the app stays responsive.
- **`onRemoved()`** (delete / replaced-by-load / shutdown): `unregister` and tear
  the resource down (close sockets, stop timers). Must be idempotent and safe even
  if the node's UI was never built.
- **Renaming** re-keys the registry: `unregister(old)` then `register(new)`.
- **Resuming on load.** Liveness being user-driven doesn't mean it's lost across a
  restart: a resource node that also implements `AutoStartable` persists whether it
  was live (a `"running"` flag in its `saveState()`) and reopens the connection
  automatically when the saved graph is reloaded — the earlier user "Connect" is
  what's being honored, not the mere presence on the canvas. The resume runs after
  the whole graph loads (so `onActivated()` and any input edges are already in
  place). See [ui.md](ui.md#resuming-running-nodes-on-load-autostartable).

`DiscordBotNode` is the canonical implementation — read it alongside this doc. It
registers a `DiscordBot` under a chosen name, forwards incoming messages/slash
commands into the registry as events, resolves its token from `SecretsStore` (so
the token is never wired or saved), and connects/disconnects on user action.

Action and trigger nodes on the other side use `find(name, …)` to call the
resource, or `subscribe(name, …)` to react to its events — see the
`graph/nodes/discord/` and `graph/nodes/resource/` nodes.

> **Not everything long-lived is a registry resource.** Where a connection is genuinely
> point-to-point, a plain **data edge** is clearer than a name lookup — the wire shows the
> dependency on the canvas. The data-store node is the example: it hands its
> `JsonDocumentStore` to a web-server node over its `Store` output edge rather than
> registering by name (see [integrations.md](integrations.md)). Reach for the registry when a
> resource is *broadcast* — referenced from many places, or by trigger nodes that may not
> exist yet — not merely because it's long-lived.

## Related: `SlashCommandRegistry` (declaration before connection)

`SlashCommandRegistry` is a separate name-keyed registry showing the same
"declare independently of order" idea: slash-command nodes *declare* the commands
they provide (keyed by bot name) whenever they like; the bot node reads the full
declared set for its name when it connects and syncs it to Discord then. Declaring
doesn't talk to Discord — connecting does. The natural rule: set up command nodes,
then Connect; changing a command afterward means a reconnect to apply it.

---

**When you change this, update…** this file (and the `ResourceRegistry` Javadoc)
whenever you change the register/find or publish/subscribe semantics, the
resource-node lifecycle contract, or add a registry like `SlashCommandRegistry`.
