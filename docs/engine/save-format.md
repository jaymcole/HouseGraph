# Save format

`GraphFileIO` serializes a canvas to JSON and back, reusing the index-based
`snapshot` shape (`GraphSnapshot`, `ClipboardNode`, `ClipboardDataEdge`,
`ClipboardFlowEdge` in `ui/snapshot/`) built for copy/paste.

The JSON conversion — `toJson` and `fromJson` — is free of any JavaFX or
`GraphCanvas` dependency so the format can be unit-tested headlessly. `save` and
`load` are the thin wrappers that touch a real canvas.

## Shape

```jsonc
{
  "version": 2,                    // format version; absent = pre-versioning (legacy)
  "plugins": [                     // node libraries this graph depends on; omitted when core-only
    { "id": "housegraph-discord", "name": "Discord", "version": "0.3.1",
      "repository": "https://github.com/jaymcole/housegraph-discord" }
  ],
  "nodes": [
    { "type": "<stable type id>",     // NodeRegistry.persistentTypeId
      "plugin": "housegraph-discord", // which library provides it; absent for a built-in
      "x": 0.0, "y": 0.0,
      "executionPolicy": "QUEUE",     // DROP | RESTART | QUEUE | PARALLEL; absent = QUEUE
      "inputs":  [ { "name": "V1", "value": 3.0 } ],   // keyed by port name
      "outputs": [ { "name": "Sum", "value": null } ], // computed values written as null
      "requiredInputs": [ "V1" ],     // names of required inputs; absent when none are
      "state":   { }                  // optional saveState() map
    }
  ],
  "dataEdges": [ { "sourceNode": 0, "sourceVariable": "Sum",
                   "targetNode": 1, "targetVariable": "V1",
                   "waypoints": [ {"x":0,"y":0} ] } ],
  "flowEdges": [ { "sourceNode": 0, "sourcePort": "True",
                   "targetNode": 1, "targetPort": 0,
                   "waypoints": [ ] } ],
  "camera": { "zoom": 1.0, "translateX": 0.0, "translateY": 0.0 } // pan/zoom; absent = default view
}
```

## Rules to preserve

**Nodes are identified by a stable type id, not a class name.** `type` is
`NodeRegistry.persistentTypeId`: the node's simple class name by default, which
already survives moving the class between packages and category folders, or an
explicit `@Node.Type` id. On load, `NodeRegistry.resolveClass` matches it against
an index of every type's ids — simple names plus `@Node.Type` ids and aliases —
falling back to fully-qualified-class-name resolution for older saves.

**The root is versioned.** `GraphFileIO.CURRENT_VERSION` is 2; a file without it
reads as legacy. `GraphFileIO.migrate` is the single seam for structural migrations
that shape-sniffing reads cannot express. Bump the version and add a step there
together. Version 2 added the `plugins` table and the per-node `plugin` key, both
purely additive, so `migrate` passes v1 files straight through with no step.

**Nodes record which library provides them.** A built-in node writes no `plugin`
key, so a graph using only core nodes produces a v2 file differing from its v1 form
by exactly the version number. Otherwise `plugin` names a row in the root `plugins`
table, which carries the library's name, version and repository URL. That table is
what the load-time dependency check reads in a single pass before any node is built
or any class is loaded, and it is what lets `resolveClass` disambiguate a type id
claimed by two libraries.

Those extra fields come from the `PluginDirectory` passed to `save`, which
`PluginCatalog` implements. Without one, a row degrades to a bare `id`: enough to
name the missing library, not enough to offer to install it. Re-saving on a machine
that has the library repairs the file. A `MissingNode`'s row is re-emitted verbatim
and never regenerated, because the file it came from may hold a version or key this
build does not know.

**Ports are persisted by name, not position.** Values are `{name, value}` objects
matched to inputs by name on load. A data or flow edge references its
variable/port by **name** when that name is non-blank and unique on the node, and
otherwise by positional **index** — the fallback for the unnamed single flow port
most nodes have. This lets a node author reorder or insert a port without
mis-binding old saves, which was the failure mode of the earlier positional format.
`requiredInputs` is likewise a list of names.

**Only persistent values are written** (`NodeVariable.isPersistentValue`).
Computed, secret and transient values are written as `null`, keeping stale data and
credentials out of files.

**`state` is loaded before ports are touched**, so dynamic-port nodes rebuild their
ports from state before values are applied.

**The camera is not part of `GraphSnapshot`.** Pan/zoom is a view concern that copy/
paste has no use for, so it is read and written directly against the root — `save`
pulls it from `GraphCanvas.getCameraState()`, and `load` restores it with
`GraphCanvas.setCameraState` via `GraphFileIO.cameraFromJson` — rather than flowing
through `toJson`/`fromJson`'s snapshot conversion.

## Forgiving reads

The old **positional** shape still loads. Bare scalar `inputs`/`outputs` arrays,
integer edge references, and a positional `requiredInputs` boolean array are all
detected by JSON shape and read positionally. A v1 file has no `plugins` table and
no per-node `plugin` key, and reads with every node resolving to no owning library.

Missing `waypoints`, `sourcePort` and `targetPort` default sensibly. A missing or
unknown `executionPolicy` loads as `QUEUE`. An edge whose named endpoint no longer
resolves on its node is dropped rather than mis-wired. A file with no `camera` key —
every save before this one — restores the default view (zoom 1, no pan).

**Keep this behaviour when you change the format**, and document new fields.

## Unresolvable nodes

**A node whose type isn't installed is preserved, not dropped.** It loads as a
`MissingNode`: a real node that reaches the canvas, shows as misconfigured, refuses
to run, and holds the node's original JSON. `toJson` writes that JSON back
verbatim, overwriting only `x` and `y`. Re-deriving it would silently lose `state`,
`maxConcurrency`, `timeoutMillis`, `requiredInputs`, and any key a future format
adds.

Its data ports are rebuilt from the saved `inputs`/`outputs` names so named edge
endpoints still resolve. Flow ports are never persisted on a node — only referenced
by edges — so `fromJson` back-fills them from the edge lists in a pass before edges
are resolved. `GraphCanvas.copySelection` filters placeholders out, since
duplicating one would produce an empty node with no JSON behind it.

**A `null` slot means only an internal failure.** A node whose type resolves but
will not instantiate keeps an index-holding `ClipboardNode` with a `null` node;
there is no user data to preserve. It still must not shift every later node's
index. `GraphCanvas.place` builds an index-aligned lookup list with a `null` slot
per unbuilt node, places only the real nodes, and resolves edges against that list.

**Edge reconnection is per-edge and self-contained.** Each saved edge is
reconnected in isolation, and one whose endpoints no longer resolve — a node index
past the loaded count, a `null` slot, or a port a node no longer has — is dropped
with a warning instead of aborting the loop. Preserve that isolation when touching
the reconnect pass.

## File actions

The toolbar exposes three. **Quick Save** writes straight to the current file — the
one most recently saved to or loaded from — with no dialog, falling back to **Save
As…** until one exists. **Load** opens a file chooser. Saving or loading records
the file as current and persists its path (`AppPreferences.LAST_FILE`) so it
reopens on the next launch, which also seeds Quick Save's target.

A reopened graph resumes any node that was running when it was saved — see
[`../nodes/state-and-startup.md`](../nodes/state-and-startup.md).

---

**When you change this, update…** this file and the `GraphFileIO` Javadoc whenever
you change the JSON shape, the versioning or migration seam, the identity rules, or
the compatibility behaviour. A change to the `plugins` table also touches
[plugin-runtime.md](plugin-runtime.md).
