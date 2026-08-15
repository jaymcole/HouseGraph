# Node state and startup

Beyond port values, a node can persist a small map of its own configuration, and
can resume a running state when a saved graph is reopened.

## `saveState` / `loadState`

A `Map<String,String>` of node-specific config: a dropdown choice, a chosen secret
key, a directory path.

```java
@Override public Map<String, String> saveState() {
    return Map.of("mode", mode.name(), "tokenKey", tokenKey);
}

@Override public void loadState(Map<String, String> state) {
    mode     = Mode.valueOf(state.getOrDefault("mode", "DEFAULT"));
    tokenKey = state.getOrDefault("tokenKey", "");
}
```

Two rules:

- **Never put a secret in here.** It is stored verbatim in the save file. Store the
  secret's `SecretsStore` key and resolve the value at runtime.
- **`loadState` runs before ports are touched**, so a dynamic-port node can rebuild
  its ports from state before values are applied. See
  [dynamic-ports.md](dynamic-ports.md).

`state` rides only save/load. **Copy/paste does not carry it**
(`NodeRegistry.duplicate`), which is why a pasted copy of a configured node starts
clean.

## Resuming a running node: `sdk.AutoStartable`

Liveness is user-driven — a node opens its connection when the user presses
Connect, not when it lands on the canvas. `AutoStartable` is how that survives a
restart: **a node running when the graph was saved resumes when the graph is
reloaded.**

Two halves.

**Persist the flag.** Write `"running": "true"` into `saveState()` while live, and
read it back in `loadState()`. It is an ordinary state entry.

```java
@Override public Map<String, String> saveState() {
    return Map.of("running", String.valueOf(isRunning()));
}
```

**Implement the interface.**

```java
public class MyResourceNode extends BaseNode
        implements NodeContentProvider, AutoStartable {

    @Override public void autoStartIfWasRunning() {
        if (wasRunning) start();   // the same path the Connect button runs
    }
}
```

`GraphCanvas.loadSnapshot` calls it on each just-loaded node **after the whole
graph is in place** — every node placed and activated, every edge wired. That
ordering matters: the node's `onActivated()` has already registered its resource,
and its incoming data edges exist, so a node that pulls an input at start sees its
wiring.

It fires **only on load**. Paste and undo/redo never auto-start a copied resource,
which falls out of `state` not being carried by duplication.

Re-run your normal Start path, including whatever thread it already uses. If Start
does blocking work off the FX thread, `autoStartIfWasRunning()` should too.

## The "on startup" trigger

The same hook is how you write a node that fires when a graph comes up. It runs
once, after every node and edge is in place, with state already loaded — so a node
that calls `execute()` from `autoStartIfWasRunning()` fires the graph as it starts.

Use this hook rather than asking for a second lifecycle interface. The timing is
already right, and the paste rule comes free: a duplicated startup node carries no
`state`, so it never fires.

This matters most for a graph running unattended, where nobody presses Start. See
[`../engine/remote-runtime.md`](../engine/remote-runtime.md).

## A graph that deploys and then does nothing

The most common deployment mistake follows directly from all of this: **the server
opens your graph, it does not press Start.** A trigger or resource node comes back
to life only if it was running at the moment the file was saved.

If you maintain nodes people deploy, make the running state visible in the node's
UI so it is obvious what will be saved. The user-facing version of this is in
[`../guides/server-setup.md`](../guides/server-setup.md).

---

**When you change this, update…** this file whenever you change the state map
contract, the `AutoStartable` timing, or the rule about what copy/paste carries.
