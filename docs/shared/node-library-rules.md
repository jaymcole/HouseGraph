# Node library rules

Rules for building a HouseGraph **node library** — a jar of node types that
HouseGraph fetches from a GitHub repository and loads at runtime.

Every rule here has a **silent** failure mode. Break one and you get a node that
never appears, logging that vanishes, or a saved graph that cannot find its nodes
again — usually with nothing in the log to explain why. That is why they are
collected rather than left to be rediscovered.

> This file is maintained in
> [HouseGraph](https://github.com/jaymcole/HouseGraph/blob/main/docs/shared/node-library-rules.md)
> and mirrored into
> [housegraph-nodes](https://github.com/jaymcole/housegraph-nodes) and
> [housegraph-plugin-template](https://github.com/jaymcole/housegraph-plugin-template).
> Edit it there; changes here are overwritten.

---

## 1. `compileOnly` the API — never `implementation`

```groovy
compileOnly 'com.github.jaymcole:HouseGraph:v0.2.0'
```

HouseGraph supplies `housegraph-api` and its transitive `org.json` and `slf4j-api`
from its own class loader.

Bundling the API gives your library its own copy of `BaseNode`, so every node in it
fails the host's `isAssignableFrom` check during discovery and **never appears**.
Bundling `slf4j-api` gives you a second logging binding with no outputs attached, so
**all your log lines silently vanish**.

The installer rejects a jar containing either, to turn those into one clear message.

## 2. Relocate everything you bundle

**All installed libraries share one class loader.** Two libraries bundling
different versions of the same dependency would fight over it.

Anything declared `implementation` ends up in the shaded jar and needs a `relocate`
line:

```groovy
shadowJar {
    relocate 'com.example.whatever', 'io.github.you.yourlib.shaded.whatever'
    mergeServiceFiles()
}
```

## 3. Keep `mergeServiceFiles()`

Any bundled library that uses `ServiceLoader` — DJL's engine discovery, JDBC
drivers — breaks without it, at runtime, with a confusing "no provider found".

## 4. Always `@Node.Type`, prefixed with your library id

```java
@Node.Type("housegraph-yourthing.DoTheThing")
```

This pins the id your node is written under in save files, independent of its class
name. Two things follow:

- **Renaming or moving the class no longer strands saved graphs.** Without it, the
  save-file id is the simple class name.
- **You do not collide with another library.** Your library shares an id space with
  every other installed library, so an unprefixed `SendMessage` is one collision
  away from resolving to somebody else's node.

You cannot fix either after the fact without asking users to hand-edit save files.

## 5. Tag every node so search can find it

```java
@Display.Name("Send Message")
@Display.Description("Posts a message to a Discord channel.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"discord", "post", "chat", "say", "notify"})
```

HouseGraph ranks nodes by their name, class name, description, keywords, category and
library. Skip these and your node is findable only by someone who already knows what it
is called — which, in a list of every installed library's nodes, is nobody.

- **`@Node.Keywords` does the most work.** It is what surfaces your node to a user
  searching for the words they would use rather than the words you chose.
- **`@Node.Kind` is the node's role** — `ACTION`, `CONTROL`, `RESOURCE` or `DATA` — not
  its category. Category is your `categoryPrefix` and menu position; kind cuts across it.

**A node with no `@Node.Kind` matches no `kind:` search at all.** Nothing is inferred
from your category path, because it is yours to name and means nothing to the host. The
only fallback is `AutoStartable`, which implies `RESOURCE`.

Note the same `javafx.scene.Node` collision that bites `@Node.Type` (see rule 4 and the
section below) applies to `@Node.Kind` and `@Node.Keywords`.

## 6. Apply the JavaFX Gradle plugin

```groovy
plugins { id 'org.openjfx.javafxplugin' version '...' }
```

HouseGraph's published metadata names JavaFX **without** a platform classifier on
purpose, so a release built on Linux cannot pin the wrong natives into your build.
The consequence is that the unclassified artifacts OpenJFX publishes are ~300-byte
stubs. Without the plugin you get `package javafx.scene does not exist`.

## 7. Exclude `slf4j-api` from every dependency that pulls it

```groovy
implementation('net.dv8tion:JDA:5.x') {
    exclude group: 'org.slf4j', module: 'slf4j-api'
}
```

Otherwise it lands in your shaded jar and rule 1 rejects it.

**Apply the exclude to every coordinate with its own path to the module, not just
the one you declared.** JDA and jmdns each need one. DJL needs three — `ai.djl:api`,
`ai.djl.pytorch:pytorch-model-zoo` and `ai.djl.pytorch:pytorch-engine` each pull
their own transitive path to `ai.djl:api`, so an exclude on one does not cover
another. Check with `gradlew :yourlib:dependencies` before you build.

---

## Things that will bite you otherwise

**Do not import `javafx.scene.Node`.** `@Node.Type`, `@Node.Kind` and
`@Node.Keywords` all come from `io.github.jaymcole.housegraph.annotations.Node`, and
`NodeContentProvider.createNodeContent()` returns `javafx.scene.Node`. Both are
named `Node`. Write `javafx.scene.Node` fully qualified at each use, or import the
nested annotation types directly (`import ...annotations.Node.Kind;`) and write
`@Kind(...)`. This only bites when a node combines the two, so it is easy to miss until
it happens — and now that every node should be tagged, it happens more often.

**A node's static initializer runs at first instantiation, not at discovery.** The
host loads classes with `initialize = false`. So a type registered from a static
block — `ValueEditors.register(...)`, `TypeConverters.register(...)` — only takes
effect once one of your nodes exists. The symptom of assuming otherwise is "my
custom type isn't editable until I place the node twice." Registering from the
constructor avoids the question.

**`onExecuted()` reaches you on the JavaFX thread**, dispatched through the host's
callback executor, so your UI code needs no `Platform.runLater`. Work *you* start —
a socket bind, an HTTP call, a gateway login — does: keep it off the FX thread and
hop back to show the result.

**Split your teardown.** `onRemoved()` runs on the removing thread and is not time
bounded — use it for fast, thread-affine work such as stopping a `Timeline` or
unregistering a name. Anything that waits on the outside world (reaping a child
process, withdrawing an mDNS registration, logging a client out) goes in
`releaseResources()`, which runs on a worker under a per-node limit, concurrently
with every other node's. Both must be idempotent, and both must work even if the
node's UI was never built.

**The asset name matters if you publish several libraries from one repository.**
HouseGraph matches a library to its jar as `<pluginId>-<version>-all.jar`. With a
single library in the repository there is nothing to disambiguate and any name
works.

---

## What you can use

Everything in `housegraph-api`:

| Package | Provides |
| --- | --- |
| `graph` | `BaseNode`, `NodeVariable`, `FlowPort`, `Edge`, `ProcessContext`, `ExecutionPolicy`, `TypeConverters` |
| `annotations` | `@Display.Name`, `@Display.Description`, `@Node.Type`, `@Node.Kind`, `@Node.Keywords`, `@Node.Disabled` |
| `sdk` | `NodeContentProvider` (inline JavaFX UI), `AutoStartable` (resume on load), `ValueEditors`, `Secrets` |
| `logging` | `Log.get(YourClass.class)` — lands in HouseGraph's own log window and file |
| `resource` | `ResourceRegistry` — long-lived resources referenced by name rather than wired |
| `storage`, `store` | `AppDirectories`, `SecretsStore`, `JsonDocumentStore` |

The three `sdk` extension points are dispatched by the host with `instanceof`, so
implementing one is the entire opt-in. **Resolve secrets through `sdk.Secrets`**,
not `SecretsStore` directly — it does nothing different today, but it is the seam a
per-library grant would be added behind.

**The API is not stable yet.** Expect to rebuild against new versions.

---

## Node design: control or action, not both

A node should almost always be **either** control-oriented **or** action-oriented.

- **Control nodes** shape *when* and *how often* flow moves: a trigger, a timer, a
  branch, a loop, a join. Their job is deciding whether something downstream runs,
  not doing that something. HouseGraph's built-in library already ships the common
  ones, so a library rarely needs to reinvent one.
- **Action nodes** *do* something: call an API, read a sensor, write a file,
  transform data. Their flow outputs report that the node ran and, at most, which of
  a few known outcomes happened **for that one invocation** — not points on a
  schedule the node manages itself.

A node that owns its own timer *and* performs an external action duplicates a
repeating-trigger node that already exists, and cannot be reused on a different
schedule. Split it: give the action a flow-in and let a repeating trigger wired
upstream decide when it fires.

**That also makes it directly testable.** An action node with a flow-in is
exercised by calling `process()` on it. A node that owns its own timer has to have
that timer spun up and torn down before you can test the thing you actually care
about.

**If a request describes a node that would both schedule its own execution and
perform an external action, treat that as a smell** — ask whether it should be two
composable nodes before building the fused version.

**The exception is a resource node that owns a real connection lifecycle** — a bot,
a web server. There Start/Stop and state genuinely belong to the same node, because
the connection *is* what is being managed. Treat that as a named exception, not as
precedent for fusing scheduling into an ordinary action node.

---

## A word about trust

A node library runs **inside HouseGraph's JVM with the user's full privileges**:
their files, their network, their saved secrets. There is no sandbox —
`SecurityManager` is gone in Java 21+ and the module system carries no permission
model.

Installing a node library is exactly as dangerous as running any other program you
downloaded, and HouseGraph says so when installing one. Treat other people's trust
accordingly: say plainly what your library does, and do not ask for a secret you do
not need.

---

## Checklist

- [ ] `compileOnly` on `housegraph-api`
- [ ] Every bundled dependency has a `relocate` line
- [ ] `mergeServiceFiles()` kept
- [ ] Every node has `@Node.Type`, prefixed with the library id
- [ ] Every node has `@Node.Kind`, `@Display.Description` and `@Node.Keywords`
- [ ] `org.openjfx.javafxplugin` applied
- [ ] `slf4j-api` excluded from every dependency with a path to it
- [ ] `javafx.scene.Node` never imported
- [ ] Teardown split between `onRemoved()` and `releaseResources()`, both idempotent
- [ ] Single jar, or assets named `<pluginId>-<version>-all.jar`
- [ ] Built jar contains no `housegraph-api`, no `org.slf4j`, no SLF4J provider
