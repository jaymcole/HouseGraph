# Publishing a node library

A node library is a jar of `BaseNode` subclasses compiled against
`housegraph-api`, published as a GitHub release, and installed at runtime by
HouseGraph. The app never has to be rebuilt.

Start from the
[plugin template](https://github.com/jaymcole/housegraph-plugin-template). The
first-party libraries live as subprojects of
[housegraph-nodes](https://github.com/jaymcole/housegraph-nodes), which shares its
build rules through a `housegraph-node-library` convention plugin in `buildSrc`, so
a subproject declares only its identity.

## Depending on the API

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

**On that coordinate.** JitPack documents multi-module projects as
`com.github.<user>.<repo>:<module>:<tag>`, and `housegraph-api/build.gradle`
publishes under exactly that name. JitPack found only one artifact in the build and
relocated it to the repository-level coordinate, so
`com.github.jaymcole.HouseGraph:housegraph-api:v0.2.0` returns 404 while
`com.github.jaymcole:HouseGraph:v0.2.0` resolves. The artifact named `HouseGraph`
*is* `housegraph-api`; `:app` has no publication. If a second module is ever
published from that repository, JitPack switches to per-module coordinates and this
one stops working.

**The API is not stable.** Expect to rebuild against new versions.

## Two build requirements with sharp failure modes

### `compileOnly`, never `implementation`

The host supplies the API and its transitive `org.json` and `slf4j-api` from the
parent class loader.

Bundling them gives your library its own `BaseNode`, so every node in it fails the
host's `BaseNode.isAssignableFrom` check during discovery **with no explanation**,
and its own SLF4J binding, so every log line silently vanishes. The installer
rejects a jar containing either, turning those into one clear message.

### Apply `org.openjfx.javafxplugin` yourself

The published POM and Gradle module metadata name JavaFX *without* a platform
classifier, deliberately: the classifier is stripped on publish so a POM built on
JitPack's Linux runner cannot pin `:linux` into a Windows author's dependency
graph.

The consequence is that the unclassified artifacts OpenJFX publishes are ~300-byte
stubs. Without the plugin you get `package javafx.scene does not exist`.

### Relocate everything you bundle

**All installed libraries share one class loader** (see
[`../engine/plugin-runtime.md`](../engine/plugin-runtime.md)). Two libraries
bundling different versions of the same dependency would fight over it.

Anything you declare `implementation` ends up in the shaded jar and needs a
`relocate` line in `shadowJar`:

```groovy
shadowJar {
    relocate 'com.example.whatever', 'io.github.you.yourlib.shaded.whatever'
    mergeServiceFiles()
}
```

### Keep `mergeServiceFiles()`

Any bundled library using `ServiceLoader` — DJL's engine discovery, JDBC drivers —
breaks without it, at runtime, with a confusing "no provider found". The
`housegraph-node-library` convention plugin in `housegraph-nodes` already does
both; if you are working from the template, do not remove them.

## Exclude `slf4j-api` from every dependency that pulls it

A bundled library's own SLF4J dependency ends up in your shaded jar unless
excluded, and the host rejects the jar for it — a bundled `slf4j-api` means a
second, silently-swallowing logging binding.

```groovy
implementation('net.dv8tion:JDA:5.x') {
    exclude group: 'org.slf4j', module: 'slf4j-api'
}
```

**Apply it to every coordinate with its own path to the module, not just the one
you declared.** JDA and jmdns each need one. DJL needs three: `ai.djl:api`,
`ai.djl.pytorch:pytorch-model-zoo` and `ai.djl.pytorch:pytorch-engine` each pull
their own transitive path to `ai.djl:api`, so an exclude on one does not cover
another's.

Check with `gradlew :yourlib:dependencies` before you build, rather than
discovering it from a failed jar-content check.

## Always `@Node.Type`, prefixed with your library id

```java
@Node.Type("housegraph-yourthing.DoTheThing")
```

**In an out-of-tree library this is a rule, not an optimisation.** In-repo nodes can
rely on the simple class name as their save-file id, because nothing else claims it.
Your library shares an id space with every other installed library, so an unprefixed
`SendMessage` is one collision away from resolving to somebody else's node.

`@Node.Type` also pins the id independently of the class name, so renaming or moving
the class does not strand every graph anyone saved using it. You cannot fix that
after the fact without asking users to hand-edit their save files.

## Registering types from a static block

`ValueEditors.register(...)` and `TypeConverters.register(...)` are usually called
from a static block. Node discovery loads classes with `initialize = false`, so that
block runs at first **instantiation**, not at scan time.

The symptom of assuming otherwise is "my custom type isn't editable until I place
the node twice." Registering from the constructor instead avoids the question
entirely.

## The `Node` import collision

`@Node.Type` comes from `io.github.jaymcole.housegraph.annotations.Node`.
`NodeContentProvider.createNodeContent()` returns `javafx.scene.Node`. Both are
named `Node`.

**Do not import `javafx.scene.Node`.** Write it fully qualified at each use. The
template's `HelloWorldNode` already does. This only bites when a node combines the
two, so it is easy to miss until it happens.

## Asset naming

A release with a **single** jar needs no convention.

A repository publishing **several** libraries at once attaches a jar each, and
`Release.assetFor(pluginId)` matches on `<pluginId>-<version>-all.jar`. That naming
is load-bearing: without it an update takes whichever jar was attached first rather
than the one it already has.

The trade a monorepo makes is lockstep versioning — one tag releases every library
at the same version — in exchange for one commit and one tag when the API changes,
rather than one PR and one tag per repository.

## What you can use from the host

| Type | Purpose |
| --- | --- |
| `graph/` | `BaseNode`, `NodeVariable`, `FlowPort`, `ProcessContext` — the model |
| `sdk.NodeContentProvider` | Inline JavaFX UI |
| `sdk.AutoStartable` | Resume a running node when a graph is reopened |
| `sdk.ValueEditors` | Register a custom manually-editable type |
| `sdk.Secrets` | Read a credential by reference |
| `resource.ResourceRegistry` | Name-keyed lookup and event pub/sub |
| `storage.AppDirectories` | On-disk locations |
| `logging.Log` | Logging |

All three `sdk` extension points are dispatched by the host with `instanceof`, so
implementing one is the entire opt-in.

**Resolve secrets through `sdk.Secrets`**, not `SecretsStore` directly. It does
nothing different today; it exists so a per-library grant can be added host-side
without every library having to change.

## Users must trust you

A node library is **arbitrary code running in the user's JVM with their full
privileges**. It can read their secrets store, filesystem and network. There is no
sandbox and there will not be one.

Say what your library does plainly in its README. Do not ask for a secret you do not
need. See [`../engine/security-model.md`](../engine/security-model.md).

## Checklist

- [ ] `compileOnly` on `housegraph-api`
- [ ] `org.openjfx.javafxplugin` applied
- [ ] Every bundled dependency has a `relocate` line; `mergeServiceFiles()` kept
- [ ] `slf4j-api` excluded from every dependency with a path to it
- [ ] Every node has `@Node.Type`, prefixed with the library id
- [ ] `javafx.scene.Node` never imported
- [ ] `META-INF/housegraph-plugin.json` manifest present
- [ ] Single jar, or assets named `<pluginId>-<version>-all.jar`
- [ ] Built jar contains no `housegraph-api`, no `org.slf4j`, no SLF4J provider
- [ ] Nodes follow [guidelines.md](guidelines.md)

---

**When you change this, update…** this file whenever the published coordinate, the
consumer build requirements, the manifest format, or the asset-naming convention
changes. The host side is in
[`../engine/plugin-runtime.md`](../engine/plugin-runtime.md).
