# Plugin runtime

Node implementations live in their own GitHub repositories. HouseGraph fetches them
at runtime from a repository URL the user supplies and loads them into the running
app. This repository keeps the core: the UI, the engine, the node model, and the
dependency-free primitives.

Splitting them lets an integration ship on its own schedule, and lets someone write
a node for their own hardware without forking the app. It also keeps JDA, DJL and
jmdns out of the core build.

Writing a library is covered in
[`../nodes/publishing-a-library.md`](../nodes/publishing-a-library.md); installing
one as a user is in [`../guides/node-libraries.md`](../guides/node-libraries.md).

## The host-side classes

All headless, in `app`'s `plugin/` package, so they are testable — this repository
has no way to test a window.

| Class | Role |
| --- | --- |
| `PluginManifest` | Reads `META-INF/housegraph-plugin.json` **without loading a class** |
| `PluginCatalog` | What is installed → `config/plugins.json`, written atomically |
| `RepositoryUrls` | Repository-URL normalisation and matching: `.git` suffix, trailing slash, case |
| `GitHubReleases` | Latest-release lookup and asset selection, restricted to GitHub hosts |
| `PluginInstaller` | Download, validate, hash, record; prunes superseded versions at startup |
| `AutoInstallPlan` | The pure install/update/refuse decision. Daemon only |
| `PluginLoader` | One shared parent-first `URLClassLoader`, plus the `ScanRoot`s |

`PluginManifest` reads metadata rather than using a `ServiceLoader` provider
because reading a provider means loading and linking a class, and the most
important job this metadata has is explaining why a library *could not* be loaded.
Metadata must be readable from a jar you have decided not to trust.

`PluginCatalog` gets its own file because `AppPreferences` is string-values-only.

## Class loading is parent-first

The shared loader resolves through the parent first, and this is required rather
than stylistic.

SLF4J 2 binds once, through `ServiceLoader` against `LoggerFactory`'s own loader —
the app loader, which finds this project's bundled provider and only that. Under
parent-first, a library's `org.slf4j` references resolve to the parent's classes,
so a library embedding something chatty has its logs land in the same `LogManager`
as everything else.

Child-first, or a library bundling its own `org.slf4j`, produces a second binding
routed into a second `LogManager` with no sinks attached, and the logs vanish with
no error anywhere.

The two things a per-library loader would buy are covered elsewhere: dependency
isolation by shading, and owner identity by the scan-time map.

**Install-time validation** rejects a jar bundling `housegraph-api`, `org.slf4j`,
or an SLF4J provider. This is not a security control — see
[security-model.md](security-model.md) — it turns three baffling runtime symptoms
into one clear message.

## Discovery across libraries

`NodeRegistry` is an instance rather than a pile of statics, because the set of
node types is not fixed for the life of a run. It scans a list of `ScanRoot`s — a
package, the loader that can load it, the owning library, and the menu category
those nodes nest under — and the app's own library is just another root.

`setRoots` swaps them and drops the index, so installing a library does not need a
restart. Each discovered type records its owning library, which is what
`resolveClass(type, pluginId)` uses to disambiguate a type id claimed by two
independently-written libraries.

`discover()` returns an `Entry(nodeClass, categoryPath, displayName)` per concrete
`BaseNode` subclass found, scanning both exploded directories and jars. The UI
builds the Add-Node menu from this, grouped by the subpackage the class sits in.
`@Node.Disabled` hides a class from the menu while keeping it loadable, so a graph
saved while a type was enabled still opens.

`instantiate(class)` builds a node via its no-arg constructor. `duplicate(source)`
clones for copy/paste by copying input/output values positionally, since
`configure*` always builds the same list shape for a given class. It carries across
**only persistent values**, exactly as saving does, so computed outputs and values
resolved off an edge — a secret in particular — are left out rather than pasted in
as manual entries.

## Startup makes no network call

The loader reads only the local catalog and cached jars. Everything
network-shaped in the app is user-initiated. A library whose jar has gone missing
is skipped with a warning; its nodes become placeholders and the graph still opens.

This is also why the app never auto-installs: it would put a network round-trip
between launching and seeing your graph. Unattended, the decision moves earlier —
see [remote-runtime.md](remote-runtime.md).

## Rate limits shape `GitHubReleases`

Unauthenticated `api.github.com` allows 60 requests per hour per IP, so updates are
never checked automatically; every lookup is a user action.

The unattended sync in [remote-runtime.md](remote-runtime.md) polls with
`git ls-remote`, which speaks the git protocol and is not rate limited that way,
which is why it can afford to run on a timer and this cannot.

## One repository may publish several libraries

A monorepo releases every library at once, attaching a jar each, so a `Release`
carries all of them and the user picks which to install. Updates do not ask:
`Release.assetFor(pluginId)` matches on the convention the template enforces,
`<pluginId>-<version>-all.jar`, so an update takes the jar it already has rather
than whichever was attached first. A release with a single jar needs no convention,
so someone forking the single-library template never has to think about asset
naming.

## Opening a graph that needs a library

`GraphDependencyCheck.inspect` compares the save file's root `plugins` table
against the catalog in one pure pass, before any node is built or any class is
loaded. `App` collapses both load paths into `openGraph(file, interactive)`, and
what happens next depends on who asked:

- **The user chose the file** — a dialog lists what is missing and offers *Open
  anyway* (the default) or *Install and open*. Opening anyway is safe because of
  `MissingNode`.
- **Startup reopen** — never blocks and never touches the network. It runs after
  `stage.show()`, so a modal would appear over an already-rendered canvas, and
  someone reopening the app wants their graph rather than a network-dependent
  prompt. A toolbar notice links to the library window instead.

An install offered from a save file goes through the same per-repository
confirmation as any other. A library installed at an *older* version than the graph
was saved against is reported but does not block; its nodes still resolve, they may
just lack a newer feature.

**Known limitation:** a v1 file has no `plugins` table, so the check reports nothing
even when the graph uses an uninstalled library's node. Those nodes are still
preserved as placeholders, but with no repository recorded there is nothing to
offer. The first save under v2 fixes it permanently.

## Changing a library while its nodes are on the canvas

Updating, disabling, enabling or removing a library always writes through to the
catalog, and for an update downloads the new jar to its own version-stamped path.
None of that touches the shared `PluginLoader` or any jar it has open, so it is
safe whatever is live.

Reinstalling the *exact same* version — typically removing a library and adding it
straight back before a restart — resolves to that same version-stamped path, which
can still be open if a node from it was live when it was removed. `PluginInstaller`
checks the freshly downloaded jar's hash against whatever is already at that path
first: if they match, it reuses the file on disk instead of overwriting it, so
there is nothing for an open handle to block. Only a genuine mismatch — the file
at that path differs from what was just downloaded, and is still open — reaches
the filesystem move and can fail.

What cannot safely happen while any node-library node is on the canvas is the
in-memory hot reload, `App.tryReloadNodeLibraries`. Rebuilding the shared class
loader re-scans every enabled library's classes, not just the changed one, so a node
still bound to the old `Class` object would be stranded — the same type existing
twice — the instant the reload ran.

`GraphCanvas.hasLiveLibraryNodes()` is the gate. `tryReloadNodeLibraries()` runs the
reload and returns `true` only when it is empty; otherwise it does nothing and
returns `false`, leaving the saved change to take effect on the next restart, which
always rebuilds `PluginLoader` and `NodeRegistry` from the catalog on disk.

Because the reload is all-or-nothing across every library, `PluginWindow` gates on
*any* library node being live anywhere, not just nodes from the library being
changed. A deferred library shows "Pending restart" in its Status column with a
live node count. The first deferral in a session raises one summary alert; later
ones only update the status line, so a batch of changes does not produce a dialog
per action.

`GraphCanvas.countLiveNodesFrom(pluginId)` drives the per-row count and
`hasLiveLibraryNodes()` gates the reload — different methods for that reason.

A library removed from the catalog leaves its jar directory on disk, since the
running loader may still have it open. `PluginInstaller.pruneSupersededVersions`
deletes it at the next startup, before any loader exists.

---

**When you change this, update…** this file whenever you change the module split,
the manifest format, the class-loading model, discovery, or the install and update
flow. Changes to the published extension points also touch
[`../nodes/`](../nodes/); trust decisions belong in
[security-model.md](security-model.md).
