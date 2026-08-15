# 0004 — Preserve unresolvable nodes verbatim (save format v2)

## Context

A node whose type could not be resolved — because its library was not installed —
became a `null` slot that never reached the canvas and was never written back.

Opening a graph without its library and pressing Quick Save therefore **permanently
destroyed** that node, its values, its state, and every edge touching it. `App`
auto-reopens the last file at startup, so it could happen before the user saw
anything.

Separately, a `null` slot shifted every later node's index, so one unbuilt node
silently misdirected every edge after it.

## Decision

**`MissingNode`** — a real node that reaches the canvas, shows as misconfigured,
refuses to run, and holds the node's original JSON. `toJson` writes that JSON back
**verbatim**, overwriting only `x` and `y`.

Re-deriving the row instead would silently lose `state`, `maxConcurrency`,
`timeoutMillis`, `requiredInputs`, and any key a future format adds.

**Save format version 2**, adding a root `plugins` table and a per-node `plugin`
key, so the load-time dependency check can name a missing library *and* the
repository it can be installed from, in one pure pass before any class is loaded.

**A `null` slot now means only an internal failure** — a type that resolves but will
not instantiate, where there is no user data to preserve. `GraphCanvas.place` builds
an index-aligned lookup list so a `null` never shifts later indices.

## Consequences

Opening a graph without its library is safe, which is what makes "Open anyway" the
default in the dependency dialog.

The version stamp exists to signal this. A v1-era build opening a v2 file would
reintroduce the data loss.

Both additions are purely additive, so `migrate` passes v1 files straight through
with no step, and a graph using only core nodes produces a v2 file differing from
its v1 form by exactly the version number.

**Known limitation:** a v1 file has no `plugins` table, so the check reports nothing
even when the graph uses an uninstalled library's node. Those nodes are still
preserved, but with no repository recorded there is nothing to offer. The first save
under v2 fixes it permanently.

For a period the writer emitted a bare `{"id": …}` row while the docs described the
full one, so files written then can name a missing library but not say where to get
it. Re-saving on a machine that has the library repairs them.

**Reference:** [`../engine/save-format.md`](../engine/save-format.md)
