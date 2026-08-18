# Node guidelines

What a good HouseGraph node does. These apply to built-in and out-of-tree nodes
alike.

## Do one thing

A node is a unit a user wires up, not a program. If it has a mode dropdown that
changes what it fundamentally does, it is probably two nodes. `Add` and `If` are
the right size.

Prefer several small nodes over one configurable one. The graph is the place
composition happens.

## Be control-oriented or action-oriented, not both

- **Control nodes** shape *when* and *how often* flow moves: a trigger, a timer, a
  branch, a loop, a join. Their job is deciding whether something downstream runs,
  not doing that something. The built-in library already ships the common ones.
- **Action nodes** *do* something: call an API, read a sensor, write a file,
  transform data. Their flow outputs report that the node ran and, at most, which
  of a few known outcomes happened **for that one invocation** — not points on a
  schedule the node manages itself.

A node that owns its own timer *and* performs an external action cannot be reused
on a different schedule, is harder to test because the two are welded together, and
duplicates a repeating-trigger node that already exists. Split it: the control
node's flow-out wires into the action node's flow-in. The action node's branches
then describe outcomes, which is what branches are for.

**The exception is a resource node that owns a real connection lifecycle** — a bot,
a web server. There Start/Stop and state genuinely belong to the same node, because
the connection *is* what is being managed. Treat that as a named exception, not as
precedent for fusing scheduling into an ordinary action node. See
[long-lived-resources.md](long-lived-resources.md).

## Tag your node so it can be found

A node nobody can find may as well not ship. The Add-Node menu only helps someone who
already knows which folder to look in, so three annotations carry the rest:

```java
@Display.Name("Add")
@Display.Description("Adds two numbers together.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"plus", "sum", "+", "arithmetic"})
public class AddNode extends BaseNode { ... }
```

- **`@Node.Keywords` is the one that earns its keep.** It is how someone finds a node
  whose name they could never have guessed — the synonyms, symbols and near-misses they
  reach for first. `Add` is found by "plus" and "+" only because it says so.
- **`@Display.Description` is written for someone who has not found the node yet.**
  "Joins a list into a single string, one entry per line" earns its place; "List to
  string node" repeats the name and does not.
- **`@Node.Kind` is the node's role, not its folder.** `ACTION`, `CONTROL`, `RESOURCE`
  or `DATA` — the same four this page describes above. A library's nodes usually share
  one category and split across several kinds.

A node that declares no kind matches no `kind:` search at all. Nothing is inferred from
the folder name, because an out-of-tree library's category path is arbitrary, and a
wrong kind is worse than none: a user who filters by it never sees the node and has no
way to tell why. The one exception is `AutoStartable`, which implies `RESOURCE` when
nothing was declared.

Search is ranked, so a misspelling still finds the node — but only across text the node
actually carries. Ports are not indexed. See
[`../engine/node-search.md`](../engine/node-search.md).

## Never persist a computed or secret value

Only manually-authored, non-secret, non-transient values reach a save file.

- Computed outputs are recomputed on load. Do not try to save them.
- Mark anything holding a credential with `markSecret()`. Persist a *reference* to
  a secret — its `SecretsStore` key — never the value.
- Mark live runtime handles with `transientValue()`.

See [ports-and-values.md](ports-and-values.md).

## Never hardcode a path

Ask `AppDirectories` for the right folder. A node's private storage is
`AppDirectories.get().nodeStorage(key)`.

## Name your ports for the canvas

Port names are read by users on a small rectangle. `Interval (s)` beats
`intervalSeconds`. Include units.

Ports are persisted **by name**, so renaming a port breaks existing saves in a way
that reordering does not. Choose names once.

## Mark inputs required when a missing value makes the node meaningless

`required()` makes a node show as misconfigured — red border, red port, tooltip —
when the input has neither an incoming edge nor a manual value. That is far better
than throwing at runtime.

The string converters' `in`, the viewers' displayed value, the `If` nodes'
condition and the object decomposer's `Object` are all required. `Add`'s operands
are not, because 0 is a sensible default.

## Give a sensible default where one exists

An input that can reasonably default should default, and should not be `required()`.

## Do not block the FX thread

If your node has inline UI, the button handler runs on the FX thread. Any network
call, socket bind or process spawn goes to a worker, with `Platform.runLater` for
the UI update afterwards.

## Split your teardown

`onRemoved()` is for fast, thread-affine work: stop a `Timeline`, reset a control,
unregister a name. `releaseResources()` is for anything that waits on the outside
world: kill a child process, withdraw an mDNS registration, log a client out.

Both must be **idempotent** and must work even if the node's UI was never built.
Blocking work left in `onRemoved()` runs unbounded on the shutdown thread and can
hang a restart. See [long-lived-resources.md](long-lived-resources.md).

## Liveness is user-driven

Being on the canvas is not the same as being live. A node that holds a connection
opens it in response to a user action — a Connect button — not in `onActivated()`.
To survive a restart, implement `AutoStartable`; see
[state-and-startup.md](state-and-startup.md).

## Poll for cancellation in anything slow

A `process()` that loops or does long CPU work should call `ctx.checkCancelled()`
periodically. Without it, a `RESTART` policy or a configured timeout cannot stop
your node until the next node boundary.

## Prefer a data edge to a name lookup

Where a dependency is point-to-point, a data edge is clearer than a
`ResourceRegistry` lookup, because the wire shows the dependency on the canvas.
Reach for the registry when a resource is *broadcast* — referenced from many
places, or by trigger nodes that may not exist yet — not merely because it is
long-lived.

## Do not import `javafx.scene.Node`

If your node uses `@Node.Type` and implements `NodeContentProvider`, the two
`Node` types collide. Write `javafx.scene.Node` fully qualified at each use.

## Ship a test

New nodes ship with a test mirroring the nearest existing one. See
[testing-nodes.md](testing-nodes.md).

## Review checklist

- [ ] Does one thing; no mode dropdown that changes its identity
- [ ] Control-oriented or action-oriented, not both (unless it owns a connection)
- [ ] `@Display.Name` set; port names readable, with units
- [ ] `@Node.Kind`, `@Display.Description` and `@Node.Keywords` set, so search can find it
- [ ] No computed or secret value persisted; secrets marked `markSecret()`
- [ ] Inputs that must have a value are `required()`; the rest have defaults
- [ ] No hardcoded paths
- [ ] No blocking work on the FX thread
- [ ] Teardown split correctly, and idempotent
- [ ] `ctx.checkCancelled()` polled in anything slow
- [ ] Test added

---

**When you change this, update…** this file whenever a new convention is agreed,
or an existing one turns out to be wrong. Keep it short enough to read before
every node.
