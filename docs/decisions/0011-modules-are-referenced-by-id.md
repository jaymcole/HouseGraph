# 0011 — A module is referenced by stable id, not by path

## Context

A module is an ordinary saved graph that another graph uses as a single node. The
consuming graph has to record *which* graph.

A file path is the obvious thing to record, and it is wrong twice over. It breaks
the moment the module is moved or renamed — the operation a user performs while
tidying, not while editing. And it is meaningless across machines: the daemon syncs
graphs between them through a git mirror under `remotes()`, where the same graph sits
at a different absolute path on every host (see
[0007](0007-sync-resets-rather-than-pulls.md) and
[`../engine/remote-runtime.md`](../engine/remote-runtime.md)).

The repository already answers the same question the same way for node types:
*nodes are identified by a stable type id, not a class name*, so moving a class
between packages does not strand old saves.

## Decision

**A module file carries `module.id` in its root, and a consumer stores that id.**
The id is a random UUID, because it must be unique across machines that have never
met and nothing about a graph's contents is both stable under editing and unique
between authors.

It is minted in exactly one place, `ModuleFile.ensureId`, called by
`ModuleLibrary.publish`. Scanning the modules directory never mints one: a read must
not rewrite the files it reads, and "this graph is now a module others may reference"
is a decision, not a side effect of looking.

**A path is kept, as a hint.** A `modules` row records where the module was last
found, and `ModuleLibrary.resolve` tries it — but only after the id-keyed scan of
`AppDirectories.modules()` has failed, and only accepts it when the file there
actually carries the id being looked for.

**The identity belongs to the file, not to the snapshot.** `toJson` builds a fresh
root from a canvas snapshot and would drop it, so `ui.io.GraphFileIO.save` reads the
file it is about to overwrite and carries the `module` object across.

## Consequences

Moving or renaming a module inside `modules()` keeps every consumer working, and a
graph referencing a module survives the round trip through a git mirror onto another
machine.

A hint that has gone stale costs one directory scan, not a broken reference. A file
that has been deleted and replaced at the hinted path is *not* silently substituted,
which a path-based reference could not have avoided.

Two module files claiming one id is possible — copying a published file makes one —
so the scan warns and keeps the first in sorted order, which is at least the same
answer on every machine. Saving a copy under a new path deliberately writes no
identity, so the ordinary way to duplicate a module produces a new graph rather than
a second claimant.

**Reference:** [`../engine/save-format.md`](../engine/save-format.md),
[`../engine/storage.md`](../engine/storage.md)
