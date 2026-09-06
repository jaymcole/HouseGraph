# Save format

`saveformat.GraphFileIO` converts a graph to JSON and back, reusing the index-based
snapshot shape (`GraphSnapshot`, `ClipboardNode`, `ClipboardDataEdge`,
`ClipboardFlowEdge`, all in `saveformat/`) built for copy/paste.

The JSON conversion — `toJson` and `fromJson` — is free of any JavaFX or
`GraphCanvas` dependency so the format can be unit-tested headlessly, and lives in
its own headless package rather than in `ui/` for the same reason `loader/` and
`headless/` do — see [architecture.md](architecture.md). `ui.io.GraphFileIO`'s
`save` and `load` are the thin wrappers that touch a real canvas, pulling a
snapshot and camera state off it and handing them to this package.

## Shape

```jsonc
{
  "version": 3,                    // format version; absent = pre-versioning (legacy)
  "plugins": [                     // node libraries this graph depends on; omitted when core-only
    { "id": "housegraph-discord", "name": "Discord", "version": "0.3.1",
      "repository": "https://github.com/jaymcole/housegraph-discord" }
  ],
  "module": {                      // THIS graph's identity as a module; only on a published module
    "id": "6f1c…", "name": "Doorbell"
  },
  "modules": [                     // the modules this graph REFERENCES; omitted when it references none
    { "id": "6f1c…", "name": "Doorbell", "path": "…/modules/doorbell.json",
      "plugins": [ { "id": "housegraph-discord", "…": "…" } ] }  // what the MODULE needs
  ],
  "nodes": [
    { "type": "<stable type id>",     // NodeRegistry.persistentTypeId
      "plugin": "housegraph-discord", // which library provides it; absent for a built-in
      "module": "6f1c…",              // which modules[] row a ModuleNode references; absent otherwise
      "x": 0.0, "y": 0.0,
      "executionPolicy": "QUEUE",     // DROP | RESTART | QUEUE | PARALLEL; absent = QUEUE
      "inputs":  [ { "name": "V1", "value": 3.0 } ],   // keyed by port name
      "outputs": [ { "name": "Sum", "value": null } ], // computed values written as null
      "requiredInputs": [ "V1" ],     // names of required inputs; absent when none are
      "state":   { },                 // optional saveState() map
      "nodeSignature": "a1b2c3d4e5f60718" // fingerprint of this node type's shape; see below
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

## Formal schema

The shape above is documentation; [`graph-save.v3.schema.json`](../../app/src/main/resources/schema/graph-save.v3.schema.json)
is the JSON Schema an external tool — an agent harness generating or validating a
graph, in particular — can actually run against a file, via `housegraph schema` or
directly from the repository. `housegraph schema graph` serves the version this
build *writes*; each superseded version stays reachable by name
(`housegraph schema graph-v2`), because a tool checking files it did not write
still needs them — an older graph is not invalid, it is older.

Each schema describes the canonical shape its build *writes*; it is stricter than
what that build *reads* (see "Forgiving reads" below), so failing validation is not
proof an existing older file won't open, only that a newly-written one doesn't match
the current format.

## Structural validation

The formal schema above catches shape errors — a missing key, a value of the wrong
JSON type — but says nothing about whether a file *makes sense*: whether an edge's
`sourceNode`/`targetNode` actually names a node, whether a `sourceVariable`/
`targetVariable`/`sourcePort`/`targetPort` resolves to a real port on that node,
whether a data edge's two ends are type-compatible, or whether the data graph
contains a cycle. Those are exactly the mistakes a hand-written or agent-generated
graph is most likely to make, and exactly what `GraphFileIO`/`GraphCanvas` are
forgiving about at load time: a dangling or mistyped data edge is dropped with only
a log line (see "Forgiving reads" above), and a data cycle loads fine and only
fails the first time something pulls a node on it, with
`IllegalStateException` from `NodeGraph.resolveInternal`.

`housegraph validate <graph.json>` (`GraphStructureValidator`) runs those checks
without a GUI session — dangling node references, unresolved ports, an
incompatible data connection, more than one data edge feeding one input, a data
cycle, a module boundary marker nothing could bind to
(`module-boundary-conflict`), and a module reference that comes back to where it
started (`module-cycle`). Like `nodes check`, it instantiates each resolvable node type to read
its real ports, so run it once a graph's libraries are installed. A node whose type
doesn't resolve is skipped for the port/type checks touching it — `check` is what
reports a missing library.

`--json` emits a report instead of log lines:

```jsonc
{
  "file": "graph.json",
  "valid": false,
  "findings": [
    { "code": "type-mismatch", "severity": "ERROR",
      "message": "a String output cannot feed a Boolean input",
      "pointer": "/dataEdges/3", "relatedPointers": [] }
  ]
}
```

Every finding's `pointer` is an RFC 6901 JSON Pointer into the save file — `/dataEdges/3`
names `root.dataEdges[3]` exactly, so a caller maps a finding back to the array
element to fix without re-deriving indices itself. `relatedPointers` names every
other location involved (the rest of a reported cycle's edges, the earlier edge a
duplicate collides with, or the sibling boundary markers a name collision spans).
A module cycle's pointer names the `modules` row *in this file* that starts the
chain, since that is the only location a pointer into this file can address; the
rest of the chain is named by id in the message.

A flow cycle is never reported: the engine's per-run `flowVisited` dedup makes a
loop back through an already-fired node a well-defined no-op, not a defect — only a
**data** cycle is a hard runtime failure.

The **module-reference** check is the one thing here that cannot be answered from
one file, so it is the one thing gated on a capability the caller hands in.
`GraphStructureValidator` does no I/O; following a reference into another graph's
file is a `ModuleResolver` passed to `inspect`. With the default
`ModuleResolver.NONE` only the cycle a file proves about itself is reported — a
graph whose `modules` table names its own `module.id`. `housegraph validate`
supplies a real one, backed by `ModuleLibrary` searching `AppDirectories.modules()`
plus the directory the graph being validated sits in, so a module checked in beside
its consumer is found without being published first. A module the resolver cannot
find simply ends that branch: an unresolvable reference is the `ModuleNode`'s
finding to report, and guessing at a cycle behind a file nobody can read would be a
false alarm.

## Dry-run / diff

`housegraph diff <current.json> <proposed.json>` (`GraphDiff`) reports what writing
`proposed.json` over `current.json` would change, without writing either file —
a dry run for a harness that generated a candidate graph and wants to review the
effect before committing to it. Like `validate`, it works from two parsed roots
(`GraphFileIO.readRoot`) and touches no node class or canvas.

Every difference is an added, removed or modified value, addressed by an RFC 6901
JSON Pointer the same way a `validate` finding is:

```jsonc
{
  "current": "graph.json",
  "proposed": "graph.proposed.json",
  "unchanged": false,
  "changes": [
    { "type": "MODIFIED", "pointer": "/nodes/2/x", "before": 100.0, "after": 140.0 },
    { "type": "ADDED", "pointer": "/nodes/3", "before": null, "after": { "type": "AddNode", "x": 0, "y": 0, "...": "..." } }
  ]
}
```

**Nodes are compared by array position**, matching how the format itself addresses
one — an edge's `sourceNode`/`targetNode` *is* that index. Inserting or removing a
node anywhere but the end shifts every later index, which shows up as a cascade of
per-field modifications rather than one clean move; that is a limitation of the
format's own addressing, not something a diff can paper over. `dataEdges`,
`flowEdges` and `plugins` carry no such positional meaning — nothing references
one by its position in those arrays — so they are matched by content (an edge) or
`id` (a plugin row) instead, meaning reordering one of those arrays alone reports
no change. A changed edge is reported as one removal and one addition rather than
a modification, since nothing identifies "the same edge" across a content change
other than its content.

`--json` emits the report above instead of `+`/`-`/`~` log lines. Exits `0` when
the two files are equivalent and `1` when a difference was found.

## Node catalog

A harness that needs to know what node types exist to build or check a graph reads
`housegraph nodes list --json` rather than the Add-Node menu: every discoverable
node type's id, category, kind, owning library, and typed inputs/outputs/flow
ports, as a versioned JSON document (`NodeCatalog.CATALOG_VERSION`). Its schema is
[`node-catalog.v1.schema.json`](../../app/src/main/resources/schema/node-catalog.v1.schema.json),
also available via `housegraph schema catalog`.

## Rules to preserve

**Nodes are identified by a stable type id, not a class name.** `type` is
`NodeRegistry.persistentTypeId`: the node's simple class name by default, which
already survives moving the class between packages and category folders, or an
explicit `@Node.Type` id. On load, `NodeRegistry.resolveClass` matches it against
an index of every type's ids — simple names plus `@Node.Type` ids and aliases —
falling back to fully-qualified-class-name resolution for older saves.

**The root is versioned.** `GraphFileIO.CURRENT_VERSION` is 3; a file without it
reads as legacy. `GraphFileIO.migrate` is the single seam for structural migrations
that shape-sniffing reads cannot express. Bump the version and add a step there
together.

| Step | Added | Migration |
| --- | --- | --- |
| v1 → v2 | the `plugins` table and the per-node `plugin` key | none — purely additive |
| v2 → v3 | the `modules` table, the per-node `module` key, and a module file's own root `module` object | none — purely additive |

Both are passthroughs, and `migrate` says so rather than being silent about it: a
step that does nothing is a decision, and the next person needs to see it was made.

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

**A module is identified by a stable id, not a path.** A graph published as a
module carries `module.id` in its root, and a consumer stores *that*. A path breaks
the moment the file is moved or renamed, and is meaningless across the machines the
daemon syncs graphs between — see
[`../decisions/0011-modules-are-referenced-by-id.md`](../decisions/0011-modules-are-referenced-by-id.md)
and [remote-runtime.md](remote-runtime.md). A `modules` row's `path` is a
*resolution hint* and nothing more: `ModuleLibrary` reads it only when the id was
not already found by scanning, and accepts it only when the file it names actually
carries that id, so a module deleted and replaced at the same path is never silently
substituted.

The id is minted in exactly one place — `ModuleFile.ensureId`, called by
`ModuleLibrary.publish`. Scanning the modules directory deliberately does *not* mint
one: a read must not rewrite the files it reads, and "this graph is now a module
other graphs may reference" is a decision, not a side effect of looking. The
identity belongs to the **file**, not to the snapshot a canvas produces, so
`ui.io.GraphFileIO.save` reads the file it is about to overwrite and carries the
`module` object across — the only place that knows which file that is. A Save As to
a new path correctly writes no identity: a copy of a module is a new graph until it
is published.

**A `ModuleNode` records which module it references, and the whole shape of it.**
`module` on the node names a row in the root `modules` table, the same way `plugin`
names a row in `plugins`, and for the same reason: the table is readable in one pure
pass before a node is built. The node keeps the same id in its own `state` — that is
where the *reference* lives, so a node round-trips through a copy/paste with nothing
but itself; the key is the pointer that makes the table readable early.

Beside it, `state` carries the **derived shape**: per port its name, whether it is
data or flow, which way it points, and for a data port the declared type's
fully-qualified name. That is not a cache. `state` is loaded before ports are
touched, so rebuilding from it is what lets a consuming graph load with its edges
bound *before* anything has gone looking for the module file — and at all when the
file is gone. See [`../nodes/dynamic-ports.md`](../nodes/dynamic-ports.md).

**A `modules` row carries the libraries that module needs.** A module built from a
Discord node needs that library wherever the module runs, including in a consuming
graph whose own canvas holds no Discord node and whose own `plugins` table therefore
never mentions one. That requirement is recorded into the consumer's `modules` row
at save time, from the module file while it is resolvable, and
`GraphDependencyCheck.requiredBy` reads the root table first and then each module
row's. The point is what it preserves: the check stays a single pure pass over one
parsed root, with no file opened and no class loaded. See
[plugin-runtime.md](plugin-runtime.md).

Per-module rows rather than a union into the consumer's own `plugins` table, because
the requirement stays attached to what introduced it: deleting the module node
deletes the row and the requirement with it, where a union would either strand rows
or need a second pass to work out which are still owed. It also round-trips verbatim
through an unresolvable module — a `ModuleNode` retains its row exactly as a
`MissingNode` retains its `plugins` row, and for the same reason: on a machine
without the module file, that row is the only record of what the module needs.

**Every real node carries a `nodeSignature`** — a 16-character hex fingerprint of
its kind, inputs, outputs, flow ports and default `saveState()` keys, computed by
`NodeSignature.of` from the live node being saved (see the
[`catalog`](../../app/src/main/java/io/github/jaymcole/housegraph/catalog) package).
Nothing here compares it — it exists so a later check can. `housegraph nodes check
<graph.json>` recomputes the signature of each node's type as currently installed
and reports any that no longer matches, which is what catches a library update that
renamed a port or changed its type before that silently breaks the graph. The same
fingerprint is what `housegraph nodes list --json`'s `signature` field reports per
node type. Absent on a save written before this field existed, and on a
`MissingNode`'s preserved row, since neither has a resolvable class to fingerprint.
A node whose ports depend on its wiring or saved state (the object decomposer, a
`ModuleNode`, a Discord slash command) reports the shape it *currently* has rather
than a fixed default, so a mismatch there is advisory — worth a look, not a reason
to fail a check on its own, the same discipline `GraphDependencyCheck` applies to an
older-than-saved library. That reasoning covers a `ModuleNode` unchanged: its ports
are its module's interface, so every edit to a module's boundary markers drifts every
graph referencing it, and drift is exactly what "the module's interface changed"
looks like from outside. Separately, `SchemaDriftCheck` instantiates a node
**without** applying its state, so a dynamic-port node's recomputed signature never
matches the saved one — pre-existing, and equally true of the object decomposer.

**Ports are persisted by name, not position.** Values are `{name, value}` objects
matched to inputs by name on load. A data or flow edge references its
variable/port by **name** when that name is non-blank and unique on the node, and
otherwise by positional **index** — the fallback for the unnamed single flow port
most nodes have. This lets a node author reorder or insert a port without
mis-binding old saves, which was the failure mode of the earlier positional format.
`requiredInputs` is likewise a list of names.

**Only persistent values are written** (`NodeVariable.isPersistentValue`).
Computed, secret and transient values are written as `null`, keeping stale data and
credentials out of files. The entry itself is still written, because its *name* is
what rebuilds a `MissingNode`'s ports and matches values by name — but **load does
not apply it back**. The same gate runs in both directions: nothing about such a
variable was ever persisted, so writing the file's `null` over it could only destroy
what the node seeded for itself at construction (a resource node's live handle, an
output's starting value), which nothing re-seeds. Skipping is keyed on the variable,
not on the value being null, so a persistent input the user genuinely cleared still
reloads cleared instead of reverting to its author default.

**`state` is loaded before ports are touched**, so dynamic-port nodes rebuild their
ports from state before values are applied.

**Writing is deterministic: an unchanged canvas re-saves to byte-identical JSON.**
`org.json`'s `JSONObject` is `HashMap`-backed, not insertion-ordered, but its key
order is a repeatable function of the exact sequence of `put` calls — and every
object `toJson` writes comes from the same fixed, hand-written sequence on every
run. The one place that sequence could come from outside this method is a node's
`state` map (`saveState()` can hand back any `Map`, and `ObjectDecomposerNode`
hands back a plain `HashMap`, whose iteration order is not part of its contract
and can in fact depend on insertion order once enough entries force a resize), so
its keys are written one at a time in sorted order rather than in whatever order
the node's own map iterates them. The `plugins` and `modules` tables are accumulated
into `LinkedHashMap`s for the same reason: their rows are written in first-reference
order on every run rather than in whatever order a `HashMap` iterates. This is what
keeps an agent's or a script's edit to one value a small, stable diff rather than an
unpredictable rewrite of surrounding keys — preserve it if `toJson` ever builds a
`JSONObject` from an externally-supplied `Map` again.

A graph that references no module writes no `modules` table and no per-node `module`
key, so it produces a v3 file differing from its v2 form by **exactly the version
number** — the same property v2 has relative to v1.

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
unknown `executionPolicy` loads as `QUEUE`. Missing `inputs`/`outputs` leave every
value at the node's own default — the schema marks both optional, so a hand-written
graph naming only a node's type and position must load rather than throw out the
whole file. An edge whose named endpoint no longer resolves on its node is dropped
rather than mis-wired. A file with no `camera` key — every save before this one —
restores the default view (zoom 1, no pan). A v2 file has no `modules` table and no
per-node `module` key, and reads as a graph that references no module — which it
could not have.

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
index. `GraphLoader` keeps the list index-aligned with the file's, `null` slots and
all, and resolves every edge against it — so the loader, not the canvas, is where
positional identity is honoured.

**An unresolvable *module* does not get a placeholder class.** `MissingNode` exists
because a node *class* could not be loaded, and nothing else could then say what the
node's ports were or what keys its JSON held. A module reference is not that case:
the `ModuleNode` class always resolves, only the referenced *file* is missing, and
everything the file would have told us — the ports, the id, the hints, the module's
own library requirements — is already in the node's own state and its retained
`modules` row. A `MissingModuleNode` would have nothing to do that `ModuleNode` does
not already do, and would have to duplicate the port rebuild to do it.

So the unresolved state is folded into `ModuleNode` itself, as
`Resolution.UNCHECKED` / `RESOLVED` / `UNRESOLVED`. `UNCHECKED` is the state after an
ordinary load, because loading does no I/O and nothing has looked yet. The guarantee
is the same either way: the ports rebuild from `state` so edges still bind, the node
reports misconfigured, it refuses to run, and a re-save writes back everything it
came in with.

**Edge reconnection is per-edge and self-contained.** Each saved edge is
reconnected in isolation, and one whose endpoints no longer resolve — a node index
past the loaded count, a `null` slot, or a port a node no longer has — is dropped
with a warning instead of aborting the loop. Preserve that isolation when touching
`GraphLoader`'s reconnect pass. Port indices resolve against the node's own
variable and flow-port lists, never against a view's ports.

## File actions

The File menu exposes three. **Save** writes straight to the current file — the one
most recently saved to or loaded from — with no dialog, falling back to **Save As…**
until one exists. **Open** opens a file chooser. Saving or loading records the file
as current and persists its path (`AppPreferences.LAST_FILE`) so it reopens on the
next launch, which also seeds Save's target.

A reopened graph resumes any node that was running when it was saved — see
[`../nodes/state-and-startup.md`](../nodes/state-and-startup.md).

---

**When you change this, update…** this file and the `GraphFileIO` Javadoc whenever
you change the JSON shape, the versioning or migration seam, the identity rules, or
the compatibility behaviour, **and** `graph-save.v3.schema.json` in the same change
— a schema that drifts from what `GraphFileIO` actually writes is worse than no
schema at all. A change to the `plugins` table, or to what a `modules` row records about a module's
own libraries, also touches [plugin-runtime.md](plugin-runtime.md); a change to how a
module is identified or resolved also touches
[`../decisions/0011-modules-are-referenced-by-id.md`](../decisions/0011-modules-are-referenced-by-id.md)
and [storage.md](storage.md). A change to `NodeSignature` — what it reads
off a node, or how it hashes — also touches `node-catalog.v1.schema.json` and
[node-search.md](node-search.md) if it changes what counts as a node's "shape".
