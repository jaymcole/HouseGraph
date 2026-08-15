# Dynamic ports

Most nodes declare a fixed set of ports. Some need ports that depend on runtime
wiring or on a user setting.

## Reacting to wiring

`onInputEdgeAdded(edge)` and `onInputEdgeRemoved(edge)` fire after a data edge to
this node is wired or unwired, dispatched through the engine's callback executor.

`ObjectDecomposerNode` in `graph/nodes/object/` is the canonical example: it grows
one output per property of whatever type is wired into its `Object` input, deriving
the properties with `ObjectProperties`.

## Reacting to settings

Call `reconfigure()` or `rebuildPorts()` to rebuild the port lists after a setting
changes — a slash-command node rebuilding its ports from its declared options, a
join node changing its arity.

## Two rules

**Guard against your own churn.** Rebuilding ports removes and re-adds edges, which
fires `onInputEdgeAdded`/`Removed` again. Without a guard you recurse.
`ObjectDecomposerNode` uses a `refreshing` flag:

```java
private boolean refreshing;

private void rebuild() {
    if (refreshing) return;
    refreshing = true;
    try {
        rebuildPorts();
    } finally {
        refreshing = false;
    }
}
```

**Persist enough to reconstruct the shape deterministically.** Save the derived
shape in `saveState()` and rebuild from it in `loadState()`. `loadState` runs
*before* ports are touched, so the ports exist by the time values are applied.

Without this, the node's shape on load depends on the order edges happen to be
reconnected — which is not a guarantee the loader makes. See
[state-and-startup.md](state-and-startup.md) and
[`../engine/save-format.md`](../engine/save-format.md).

## Edge endpoints are matched by name

Ports are persisted by name where the name is non-blank and unique on the node.
A dynamic port whose name changes between saves will not re-bind, and the edge is
dropped with a warning rather than mis-wired. Derive port names from something
stable — the property name, the option name — not from an index.

---

**When you change this, update…** this file whenever you change the edge-wiring
hooks, `rebuildPorts`, or the ordering of `loadState` relative to port
construction.
