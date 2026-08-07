# Plugins — out-of-tree node libraries

> **Status: in progress.** The refactor that makes this real is landing in phases.
> Sections marked *(not yet built)* describe the agreed design, not shipped code.
> Phases 0–5 are done: the build is split, the node-facing extension points are in
> the published API, that API is configured for publication, `NodeRegistry` scans
> multiple roots and tracks which library owns each node type, and the save format
> records and preserves them. **No tag has been pushed yet**, so no JitPack coordinate
> resolves in the real world — the publish was verified end-to-end against `mavenLocal`
> by compiling a node in a scratch project.

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

## Still to come

- **Plugin manifest** — `META-INF/housegraph-plugin.json`, generated by the library's
  build, readable without loading a class. *(not yet built)*
- **Class loading** — one shared, parent-first `URLClassLoader` over all enabled
  jars. Parent-first is not a detail: SLF4J binds once against `LoggerFactory`'s own
  loader, so parent-first keeps a library's logging in this app's single
  `LogManager` pipeline. Child-first, or a library bundling its own `org.slf4j`,
  gives a second binding with no sinks attached and its logs silently vanish.
  *(not yet built)*
- **Fetch and install** — GitHub releases lookup, download, validation, catalog.
  *(not yet built)*
- **Dependency window** and the load-time dependency check. *(not yet built)*
- **Template repository** node projects fork from. *(not yet built)*

---

**When you change this, update…** this file whenever you change the module split,
the published API surface, the manifest format, the class-loading model, the
security posture, or the set of extracted node libraries. Changes to the extension
points themselves also touch [ui.md](ui.md) and [nodes.md](nodes.md).
