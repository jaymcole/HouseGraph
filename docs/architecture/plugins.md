# Plugins — out-of-tree node libraries

> **Status: in progress.** The refactor that makes this real is landing in phases.
> Sections marked *(not yet built)* describe the agreed design, not shipped code.
> Phases 0–7 are done — everything on the host side. The build is split, the node-facing
> extension points are in the published API, that API is configured for publication,
> `NodeRegistry` scans multiple roots and tracks which library owns each node type, the
> save format records and preserves them, the runtime loads libraries from jars, and the
> app has a library window and a load-time dependency check.
>
> **No tag has been pushed yet**, so no JitPack coordinate resolves in the real world. The
> publish was verified end-to-end against `mavenLocal` by compiling a node in a scratch
> project; jar loading by compiling a node at test time into a jar it can only be reached
> through; and the whole path by building a real library jar and watching the app load it
> at startup. What remains is the template repository and moving the integration node
> categories out.

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

Published through **JitPack**, which builds a git tag and serves a multi-module
project's subprojects as `com.github.<user>.<repo>:<module>:<tag>`:

```groovy
repositories {
    mavenCentral()
    maven { url = 'https://jitpack.io' }
}

dependencies {
    // compileOnly is required, not stylistic -- see below.
    compileOnly 'com.github.jaymcole.HouseGraph:housegraph-api:v0.2.0'
}
```

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

Updating, disabling or removing a library while its nodes are on the canvas is refused with
an explanation, because Java can't unload a class while instances exist: those nodes would
stay bound to the old loader's `Class` objects and the type would exist twice.

## Still to come

- **Template repository** node projects fork from. *(not yet built)*
- **Extracting the integration node categories** — `discord`, `camera`, `web`, `ml`, `iot`.
  *(not yet started)*

---

**When you change this, update…** this file whenever you change the module split,
the published API surface, the manifest format, the class-loading model, the
security posture, or the set of extracted node libraries. Changes to the extension
points themselves also touch [ui.md](ui.md) and [nodes.md](nodes.md).
