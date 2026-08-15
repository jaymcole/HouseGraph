# Node guidelines

What a good HouseGraph node does. These apply to built-in and out-of-tree nodes
alike.

## Do one thing

A node is a unit a user wires up, not a program. If it has a mode dropdown that
changes what it fundamentally does, it is probably two nodes. `Add` and `If` are
the right size.

Prefer several small nodes over one configurable one. The graph is the place
composition happens.

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
- [ ] `@Display.Name` set; port names readable, with units
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
