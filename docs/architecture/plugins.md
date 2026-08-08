# Plugins — out-of-tree node libraries

> **Status: complete.** The host side is done and proven end-to-end. The build is
> split, `housegraph-api` is published (`com.github.jaymcole:HouseGraph`, via JitPack —
> see the coordinate note below), `NodeRegistry` scans multiple roots and tracks which
> library owns each node type, the save format records and preserves them, the runtime
> loads libraries from jars, the app has a library window and a load-time dependency
> check, and all five integrations (`iot`, `discord`, `camera`, `web`, `ml`) have been
> extracted, with old saves verified to keep working against a real installed jar each
> time. `app/build.gradle` now depends on nothing but `:housegraph-api`.

Node implementations live in **their own GitHub repositories**. HouseGraph fetches
them at runtime from a repo URL the user supplies and loads them into the running
app. This repository keeps the core: the UI, the graph engine, the node model, and
the dependency-free primitive nodes.

## Why

Adding a node used to mean committing to this repository, which coupled every
integration's release cycle to the app's and dragged JDA, DJL and jmdns into the
core build. Splitting them lets an integration ship on its own schedule, and lets
someone write a node for their own hardware without forking the app.

## The two modules

| Module | Contains | Published? |
| --- | --- | --- |
| `housegraph-api` | `graph/` (engine + node model), `annotations/`, `sdk/`, `logging/`, `resource/`, `storage/`, `store/` | **Yes** — node libraries compile against it |
| `app` | `ui/`, `App`/`Launcher`, the built-in `graph/nodes/`, integration clients, and the plugin host | No |

**Anything in `housegraph-api` is published API. A breaking change there breaks
every node library anyone has written.** Two rules follow:

- `BaseNode`'s **abstract** method set is frozen. Adding an abstract method breaks
  every existing node. New lifecycle hooks must be concrete with a default — which
  is how the existing hooks (`onActivated`, `onRemoved`, `onExecuted`,
  `configureFlowInputs`/`Outputs`, …) are already written.
- The api module must never depend on `app`.

Note `graph/` is in the api module while `graph/nodes/` is in `app`. Distinct
packages, not a split package — but worth knowing before you go looking for a class.

## The `sdk/` package

`graph/` is the model a node is built from. `sdk/` is everything else an author
reaches for, and each member is there because it must be usable from *outside* this
repository:

| Type | Purpose |
| --- | --- |
| `NodeContentProvider` | Give a node its own inline JavaFX UI. **The sole reason the api module depends on JavaFX**, declared on the `api` configuration so authors get `javafx.scene.Node` at compile time |
| `AutoStartable` | Resume a node's running state when a saved graph is reopened |
| `ValueEditors` | Make a custom value type manually editable in an inline field |
| `Secrets` | Read a credential by reference |

The first three lived in `ui/` until nodes moved out. That stopped being merely
untidy and became impossible: an out-of-tree node cannot see `app`, so an extension
point in `ui/` was unimplementable by the very code it exists for. All three are
dispatched by the host with `instanceof`, so implementing one is the entire opt-in —
there is nothing to register.

## Consuming `housegraph-api`

Published through **JitPack**, which builds a git tag:

```groovy
repositories {
    mavenCentral()
    maven { url = 'https://jitpack.io' }
}

dependencies {
    // compileOnly is required, not stylistic -- see below.
    compileOnly 'com.github.jaymcole:HouseGraph:v0.2.0'
}
```

> **On that coordinate.** JitPack documents multi-module projects as
> `com.github.<user>.<repo>:<module>:<tag>`, and `housegraph-api/build.gradle` publishes
> under exactly that name. But JitPack found only *one* artifact in the build and
> **relocated it to the repository-level coordinate**, so
> `com.github.jaymcole.HouseGraph:housegraph-api:v0.2.0` 404s while
> `com.github.jaymcole:HouseGraph:v0.2.0` resolves. Verified against the live repository by
> compiling a node against it.
>
> The artifact named `HouseGraph` *is* `housegraph-api` — `:app` has no publication and
> nobody compiles against it — so the name is misleading but harmless. Keep it in mind if a
> second module is ever published from this repository: JitPack would then switch to
> per-module coordinates and this one would stop working.

Two requirements on the consumer, both with sharp failure modes:

- **`compileOnly`, never `implementation`.** The host supplies the api and its
  transitive `org.json` / `slf4j-api` from the parent class loader. Bundling them
  gives the library its own `BaseNode` — so every node in it fails the host's
  `BaseNode.isAssignableFrom` check during discovery, with no explanation — and its
  own SLF4J binding, so every log line silently vanishes. The installer rejects a
  jar containing either, to turn those into one clear message.
- **Apply `org.openjfx.javafxplugin` yourself.** The published POM and Gradle
  module metadata name JavaFX *without* a platform classifier, deliberately: the
  classifier is stripped on publish (`pom.withXml` in `housegraph-api/build.gradle`)
  so a POM built on JitPack's Linux runner cannot pin `:linux` into a Windows
  author's dependency graph. The consequence is that the unclassified artifacts
  OpenJFX publishes are ~300-byte stubs — without the plugin you get
  `package javafx.scene does not exist`.

`jitpack.yml` pins JDK 21 and scopes the build to
`:housegraph-api:publishToMavenLocal`; `:app` needs the DJL BOM, JDA and platform
natives, has no publication, and would just be a slow way to fail. The root build
honours `-Pversion=<tag>` so the published version always matches the tag.

## Security — the honest threat model

**A node library is arbitrary code running in this JVM with the user's full
privileges.** It can read the secrets store, the filesystem, and the network
directly. There is no sandbox to hide behind: `SecurityManager` is deprecated for
removal and unusable on Java 21+, JPMS carries no permission model, and running
nodes out-of-process would break the whole `NodeContentProvider` design.

So: **installing a node library is exactly as dangerous as running any program you
downloaded.** Say that plainly in the install dialog and the README. Do not imply a
boundary that does not exist.

What is worth building anyway, in value order:

1. **Explicit install confirmation per repository**, naming owner/repo/asset/size,
   recorded as trust-on-first-use. This matters most for the load-time dependency
   check, where an *untrusted save file* proposes a code download via its recorded
   repository URL. Never auto-install from a save file. *(not yet built)*
2. **Restrict fetch origins** to `github.com`, `api.github.com`,
   `objects.githubusercontent.com`. *(not yet built)*
3. **Pin the asset by SHA-256** on install; re-verify on load. *(not yet built)*
4. **`sdk.Secrets`** — done. Nothing it does today differs from calling
   `SecretsStore.open()`; the point is the *seam*. A per-library grant checked inside
   `Secrets.get` is a host-side change, whereas retrofitting one after twenty
   published libraries call `SecretsStore` directly is not feasible. Putting the seam
   in before the first out-of-tree node exists cost nothing.

`SecretsStore`'s own Javadoc already admits the key sits beside the ciphertext, so
local secrecy is already "casual inspection" grade. Node libraries do not worsen the
local-attacker story; they create a *remote exfiltration* story that did not exist
before.

## Node discovery across libraries — done

`NodeRegistry` is an instance rather than a pile of statics, because the set of node
types is no longer fixed for the life of a run. It scans a list of `ScanRoot`s — a
package, the loader that can load it, the owning library, and the menu category those
nodes nest under — and the app's own library is just another root. `setRoots` swaps them
and drops the index, so installing a library does not need a restart. Each discovered
type records its owning library, which is what `resolveClass(type, pluginId)` uses to
disambiguate a type id claimed by two independently-written libraries.

## Save format — done

Version 2 records which library each node came from, and preserves a node whose type
isn't installed **verbatim**, so opening a graph without a library and re-saving no
longer destroys it. The root `plugins` table also carries each library's repository URL,
which is what makes an "install the missing library" offer possible at all. See
[ui.md](ui.md#save--load-graphfileio).

## The plugin runtime — done

All headless, in `app`'s `plugin/` package, so it is testable — this repository has no
way to test JavaFX windows, so nothing worth testing may live in one.

| Class | Role |
| --- | --- |
| `PluginManifest` | Reads `META-INF/housegraph-plugin.json` **without loading a class**. A `ServiceLoader` provider would have been typed and matches the repo's existing precedent, but reading it means loading and linking a class — and the most important job this metadata has is explaining why a library *couldn't* be loaded. Metadata must be readable from a jar you've decided not to trust. |
| `PluginCatalog` | What's installed → `config/plugins.json`, written atomically. Its own file because `AppPreferences` is string-values-only. |
| `GitHubReleases` | Latest-release lookup and asset selection, restricted to GitHub hosts. |
| `PluginInstaller` | Download, validate, SHA-256, record; prunes superseded versions at startup. |
| `PluginLoader` | One shared **parent-first** `URLClassLoader`, plus the `ScanRoot`s. |

**Parent-first is load-bearing, not a style choice.** SLF4J 2 binds once, via
`ServiceLoader` against `LoggerFactory`'s own loader — the app loader, which finds this
project's bundled provider and only that. Under parent-first, a library's `org.slf4j`
references resolve to the parent's classes, so a library embedding something chatty has
its logs land in the same `LogManager` as everything else. Child-first, or a library
bundling its own `org.slf4j`, gives a second binding routing into a second `LogManager`
with no sinks attached — the logs vanish with no error anywhere. The two things a
per-library loader would have bought are covered elsewhere: dependency isolation by
shading, owner identity by the scan-time map.

**Install-time validation** rejects a jar bundling `housegraph-api`, `org.slf4j`, or an
SLF4J provider. Not security — see below — just turning three baffling runtime symptoms
into one clear message.

**Startup makes no network call.** The loader reads only the local catalog and cached
jars. Everything network-shaped is user-initiated. A library whose jar has gone missing
is skipped with a warning; its nodes become placeholders and the graph still opens.

**Rate limits shape `GitHubReleases`:** unauthenticated `api.github.com` allows 60
requests/hour/IP. So updates are never checked automatically, and the stored `ETag` is
sent back as `If-None-Match` — a 304 doesn't count against the limit, making a repeat
check free.

**One repository may publish several libraries.** A monorepo releases every library at
once, attaching a jar each, so a `Release` carries *all* of them and the user picks which
to install. Updates don't ask — `Release.assetFor(pluginId)` matches on the convention the
template enforces, **`<pluginId>-<version>-all.jar`** — so an update takes the jar it
already has rather than whichever was attached first. A release with a single jar needs no
convention at all, so someone forking the single-library template never has to think about
asset naming.

## Opening a graph that needs a library — done

`GraphDependencyCheck.inspect` compares the save file's root `plugins` table against the
catalog in one pure pass, before any node is built or any class is loaded. `App` collapses
both load paths into `openGraph(file, interactive)`, and what happens next depends on who
asked:

- **The user chose the file** — a dialog listing what's missing, offering *Open anyway*
  (default) or *Install and open*. Opening anyway is safe precisely because of
  `MissingNode`; before that fix it would have been a data-loss trap.
- **Startup reopen** — never blocks and never touches the network. It runs after
  `stage.show()`, so a modal would appear over an already-rendered canvas, and someone
  reopening the app wants to see their graph rather than a network-dependent prompt. A
  toolbar notice links to the library window instead.

An install offered from a save file still goes through the same per-repository
confirmation as any other: a save file is untrusted input proposing a code download, so it
never installs silently.

A library installed at an *older* version than the graph was saved against is reported but
does not block — its nodes still resolve, they may just lack a newer feature.

**Documented limitation:** a v1 file has no `plugins` table, so the check reports nothing
even when it uses an uninstalled library's node. Those nodes are still preserved as
placeholders, but with no repository recorded there's nothing to offer. The first save
under v2 fixes it permanently.

## The library window — done

`ui/plugin/PluginWindow`, modelled on `LogWindow` rather than `SecretsEditor`: non-modal,
unowned, singleton, toggle-to-front. Installing is long and network-bound, and the user
should be able to watch the canvas and log window while it runs — a modal forbids exactly
that. It's a deliberately thin shell; everything worth testing lives in `plugin/`.

The table supports multi-selection. Update, Enable/Disable, and Remove all act on the whole
selection at once — each still goes through the same per-library gate (an install
confirmation per update, one confirmation for a batch remove) rather than a single blocker
aborting the batch. Check for Updates checks just the selection when rows are selected, or
every installed library when nothing is.

When a release publishes several libraries, the picker's dropdown shows only each asset's
name — `Asset` is a record, and its default `toString()` dumping every field made for an
unreadable list. Size is shown as a detail label below the dropdown once something is
selected, rather than crowding the dropdown itself.

### Changing a library while its nodes are on the canvas: deferred, not refused

Updating, disabling, enabling, or removing a library always writes through to the catalog
(`config/plugins.json`) and, for an update, downloads the new jar to its own version-stamped
path — none of that touches the shared `PluginLoader` or any jar it has open, so it's safe no
matter what's live. What *can't* safely happen while any node-library node is on the canvas is
the in-memory hot reload (`App.tryReloadNodeLibraries`, née `reloadNodeLibraries`): rebuilding
the shared class loader re-scans every enabled library's classes, not just the changed one, so
a node still bound to the old `Class` object would be left stranded — the same type existing
twice — the instant that reload ran. `GraphCanvas.hasLiveLibraryNodes()` is the gate:
`tryReloadNodeLibraries()` runs the reload and returns `true` only when it's empty; otherwise it
does nothing and returns `false`, leaving the just-saved change to take effect on the next
restart, which always rebuilds `PluginLoader`/`NodeRegistry` fresh from the catalog on disk.

Because the reload is all-or-nothing across every library, `PluginWindow` gates on *any*
node-library node being live anywhere on the canvas, not just nodes from the library being
changed — installing or enabling an unrelated library while something else's nodes are live
defers too, for the same reason. A library whose change is deferred shows "Pending restart" in
its Status column (with a live node count, when that library itself has one); the first time in
a session that anything defers, one summary alert says so, and later deferrals just update the
status line so a batch of changes doesn't produce a dialog per action. `GraphCanvas`'s
`countLiveNodesFrom(pluginId)` (one library) and `hasLiveLibraryNodes()` (any library) are
different methods for this reason — the former drives the per-row live-count display, the
latter gates the reload itself.

A library removed from the catalog leaves its jar directory on disk untouched (the running
loader may still have it open); `PluginInstaller.pruneSupersededVersions` deletes it at the next
startup, before any loader exists — the same moment it already prunes a superseded version's
directory for a library that's merely been updated.

## Where the node libraries live

| Repository | What it is |
| --- | --- |
| [housegraph-plugin-template](https://github.com/jaymcole/housegraph-plugin-template) | Template for **one** library in **one** repository — what a third party starts from |
| [housegraph-nodes](https://github.com/jaymcole/housegraph-nodes) | First-party libraries, as subprojects of one repository |

The first-party libraries share a repository on purpose. The API will change, and when it
does every library needs rebuilding — that's one commit and one tag in a monorepo, against
one PR and one tag per repository otherwise. The build rules are also easy to get subtly
wrong in ways that fail *silently*, so they live once in `buildSrc` as the
`housegraph-node-library` convention plugin rather than being copied per library and left
to drift. A subproject declares only its identity, in about ten lines.

The trade is lockstep versioning: one tag releases every library at the same version. That
is also why a release carries several jars, and why the asset naming convention
(`<pluginId>-<version>-all.jar`) is load-bearing rather than tidy.

### Extraction status

| Category | Status |
| --- | --- |
| `iot` | **Extracted** → `housegraph-iot`. Its Arduino firmware moved with it — firmware and the node driving it are no use apart |
| `discord` | **Extracted** → `housegraph-discord`. The hardest case, done second on purpose — see below |
| `camera` | **Extracted** → `housegraph-camera`. No third-party dependency at all, so the SLF4J-exclude lesson didn't apply; the `Node`-import collision did (three of its nodes have an inline UI) |
| `web` | **Extracted** → `housegraph-web`. Bundles jmdns, which — like JDA — transitively depends on `slf4j-api`; the exclude lesson from `discord` applied again, this time applied proactively before the build rather than discovered from a failed jar check. The `Node`-import collision applied too (both of its nodes have an inline UI) |
| `ml` | **Extracted** → `housegraph-ml`. Fifth and last, saved for last because DJL's shading and native-library size are the messiest of the five. `ai.djl:api` transitively depends on `slf4j-api` too, but `pytorch-model-zoo` and `pytorch-engine` each pull their own path to it, so the exclude is applied on all three DJL coordinates, not just the one declared directly. `AnimalClassifierNode` doesn't implement `NodeContentProvider`, so the `Node`-import collision didn't apply here |

**Extracting a category keeps old saves working**, verified for all five extractions
against a real installed jar, not only in unit tests: a graph saved while a node shipped
in the app recorded it by its bare class name with no plugin key, and that still resolves
— the registry indexes a node's simple name alongside its declared `@Node.Type` id. New
saves use the prefixed id plus the owning library. The Add-Node menu is unchanged too,
because a library's `categoryPrefix` reproduces the old category name.

**`discord` was deliberately the hardest case, done second.** It carries a sibling client
package, `SecretsStore`/`sdk.Secrets` access, `ResourceRegistry` + `Subscription`, both
`AutoStartable` and `NodeContentProvider` on the same node, dynamic ports via
`rebuildPorts`, a `saveState` map, and a third-party dependency (JDA) with its own
transitive dependencies. Doing it once `iot` had proven the pipeline meant any gap it
exposed was found while only one other library existed to fix up. Two things it exposed,
worth knowing before extracting anything else that bundles a library depending on SLF4J:

- **A bundled library's own SLF4J dependency leaks into the shaded jar unless excluded.**
  JDA depends on `slf4j-api`; left alone, that transitive dependency ends up bundled too —
  which the host's install-time validation rejects a jar for, because a bundled
  `slf4j-api` means a second, silently-swallowing logging binding. Fix:
  `exclude group: 'org.slf4j', module: 'slf4j-api'` on the dependency declaration.
  `housegraph-api` already supplies the real one, `compileOnly`.
- **`@Node.Type` (from `annotations.Node`) collides with `NodeContentProvider`'s
  `javafx.scene.Node` return type** — both are simply named `Node`, and no in-repo node
  had ever combined the two before this extraction. Fix: don't import `javafx.scene.Node`;
  write it fully qualified at each use. The template's `HelloWorldNode` already does this.

## Still to come

Nothing on the node-extraction front — all five built-in integration categories are
out-of-tree. Future work here is host-side hardening: per-plugin secret ACLs beyond
the `sdk.Secrets` seam described in [Security](#security--the-honest-threat-model)
above, and revisiting that seam now that several plugins exist to use it.

---

**When you change this, update…** this file whenever you change the module split,
the published API surface, the manifest format, the class-loading model, the
security posture, or the set of extracted node libraries. Changes to the extension
points themselves also touch [ui.md](ui.md) and [nodes.md](nodes.md).
