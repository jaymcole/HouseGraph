# CLAUDE.md — HouseGraph

Entry point for anyone, human or AI, working on this repository.

## What HouseGraph is

A JavaFX desktop app: a **node-graph editor for home automation**, with a focus on
computer-vision triggers. You wire nodes on an infinite canvas into graphs that
react to events. Graphs are saved as JSON.

Two kinds of connection run between nodes, and keeping them separate is the central
design idea:

- **Data edges** carry a typed value from one node's output to another's input,
  pulled on demand.
- **Flow edges** carry no value; they define execution order, pushed when a trigger
  fires.

This repository ships the engine, the UI, and dependency-free primitive nodes.
Every integration is an out-of-tree node library fetched at runtime.

## Build & run

```bash
./gradlew run           # launch the app (delegates to :app:run)
./gradlew test          # JUnit 5 suite in both modules
./gradlew compileJava   # fast sanity check
./gradlew :app:shadowJar   # the self-contained jar; also the CLI
```

**Java 21**, **JavaFX 21**, both provisioned by Gradle. Two modules:
`housegraph-api` (published — node libraries compile against it) and `app` (the
desktop program, nobody compiles against it). Key dependencies are `org.json` and
`slf4j-api`.

`Launcher` holds the `main` you actually run and delegates to `App`. **Do not move
`main` into `App`** — the split is what lets JavaFX launch from a plain classpath
jar.

## Where the docs live

| Section | For | Voice |
| --- | --- | --- |
| [`docs/engine/`](docs/engine/) | Changing the engine, runtime or host app | Technical reference. Present tense, states decisions and their reasons |
| [`docs/nodes/`](docs/nodes/) | Writing a node | Task-shaped how-tos and conventions |
| [`docs/guides/`](docs/guides/) | Using and operating HouseGraph | Numbered steps, commands, symptom tables |
| [`docs/decisions/`](docs/decisions/) | Why something is the way it is | Short records: Context, Decision, Consequences |

High-traffic packages carry their own `CLAUDE.md`, loaded automatically when you
edit files there.

## Invariants

These describe how the code already works. A change that breaks one needs a
deliberate decision.

1. **Data and flow are separate concepts.** Never fold one into the other.
2. **`graph/` never imports JavaFX.** Engine-to-UI notification goes through the
   injectable callback executor and `GraphExecutionListener`. This is what keeps
   the engine testable without a display.
3. **Nodes never persist computed or secret values.** Only manually-authored,
   non-secret, non-transient values are saved (`NodeVariable.isPersistentValue`).
4. **Secrets live only in `SecretsStore`, encrypted.** Nodes store a reference, and
   resolve the value at runtime.
5. **All on-disk paths go through `AppDirectories`.**
6. **Long-lived resources are referenced by name, not wired** — when they are
   genuinely broadcast. Liveness is user-driven, not tied to being on the canvas.
7. **Keep pure logic headless-testable.** `NodeGraph`, `GraphFileIO`'s JSON
   conversion and `ObjectProperties` avoid JavaFX deliberately.

## API stability

`housegraph-api` is compiled against by
[`housegraph-nodes`](https://github.com/jaymcole/housegraph-nodes) and anything
built from the
[plugin template](https://github.com/jaymcole/housegraph-plugin-template).

**The API is not stable yet.** Breaking changes are acceptable — they mean
rebuilding those repositories, so make the change there in the same pass. Prefer
adding a concrete hook with a default over a new abstract method, since that avoids
the rebuild. A preference, not a guarantee.

## Tag every PR title for release

`.github/workflows/auto-tag.yml` tags and releases every merge to `main`
automatically, bumping **patch** by default. When opening a pull request, put
`#minor` or `#major` in the PR title yourself if the change warrants it — don't
leave it to default to patch:

- `#major` — a breaking change: `housegraph-api`'s public contract changes
  incompatibly, or a built-in node's ports, id, or saved-graph-visible behavior
  change incompatibly.
- `#minor` — a backwards-compatible addition: a new built-in node, a new editable
  value type, a new `housegraph-api` hook, or a new port on an existing node that
  doesn't change what old graphs do.
- *(no tag)* — a fix, refactor, docs change, or anything else that doesn't add or
  break public surface. This is the default, so no action needed.

Get this right at PR-creation time — the tag is read from the merge commit
message, and there's no fixing it after the merge without deleting and
recreating the release tag by hand.

## Documentation mandate

**A change that alters architecture, a public contract, a subsystem's observable
behaviour, or the set of node types / editable types MUST update the relevant
documentation in the same change.**

Every file under `docs/` ends with a **"When you change this, update…"** note.
Honour it. Beyond that:

| If you change… | Also update… |
| --- | --- |
| Graph execution, threading, locking | `NodeGraph` Javadoc + [`docs/engine/`](docs/engine/) execution docs |
| `BaseNode` lifecycle hooks | `BaseNode` Javadoc + [execution-model.md](docs/engine/execution-model.md) and [node-lifecycle.md](docs/engine/node-lifecycle.md) |
| Save-file JSON format | `GraphFileIO` Javadoc + [save-format.md](docs/engine/save-format.md); keep the backward-compat notes |
| Resource registry semantics | `ResourceRegistry` Javadoc + [long-lived-resources.md](docs/nodes/long-lived-resources.md) |
| Secret storage, crypto, on-disk locations | `SecretsStore`/`AppDirectories` Javadoc + [storage.md](docs/engine/storage.md) |
| Logging levels, sinks, bootstrap | `LogManager`/`Logging` Javadoc + [logging.md](docs/engine/logging.md) |
| Anything user-facing | `README.md` and [`docs/guides/`](docs/guides/) |
| Add a node type | Nothing to register, but tag it (`@Node.Kind`, `@Display.Description`, `@Node.Keywords`). A new **category folder** goes in [`docs/nodes/README.md`](docs/nodes/README.md) |
| Node metadata, the searchable fields, or ranking | `NodeMetadata`/`NodeSearchIndex` Javadoc + [node-search.md](docs/engine/node-search.md) |
| Make a new value type editable | `sdk.ValueEditors` static block + [type-system.md](docs/engine/type-system.md) |
| Add a package | Add a `package-info.java` |

### How to write it

- **One concept, one home.** If two files need it, one owns it and the other links.
  Duplicated explanations drift apart.
- **State decisions and their reasons; do not narrate how you got there.** "Flow is
  fire-and-forget, so a slow branch cannot stall a fast sibling" belongs in the
  reference. "This used to be barriered and we changed it" belongs in
  [`docs/decisions/`](docs/decisions/), if anywhere.
- **No status commentary.** No "Status: complete", no "— done" headings, no
  roadmaps, no sections describing something that is not built. That belongs in
  issues.
- **Do not claim more than is true.** No frozen interfaces, no implied install
  base, no "verified" for something that is not checked.
- **Keep files short.** Over ~200 lines, it is probably two documents.

## Extending

| Want to | Read |
| --- | --- |
| Add a node type | [`docs/nodes/first-node.md`](docs/nodes/first-node.md) |
| Give a node inline UI | [`docs/nodes/inline-ui.md`](docs/nodes/inline-ui.md) |
| Add a named-resource integration | [`docs/nodes/long-lived-resources.md`](docs/nodes/long-lived-resources.md) |
| Ship nodes as a library | [`docs/nodes/publishing-a-library.md`](docs/nodes/publishing-a-library.md) |
