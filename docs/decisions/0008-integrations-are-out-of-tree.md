# 0008 — Integrations live in out-of-tree node libraries

## Context

Adding a node meant committing to this repository. That coupled every integration's
release cycle to the app's, and dragged JDA, DJL and jmdns into the core build.
Writing a node for your own hardware meant forking the app.

## Decision

Split the build into `housegraph-api` (published) and `app` (not), and move every
integration category into its own repository, fetched at runtime.

All five were extracted: `iot`, `discord`, `camera`, `web`, `ml`.
`app/build.gradle` now depends on nothing but `:housegraph-api`. Old saves were
verified to keep working against a real installed jar at each step, not only in
unit tests.

The order was chosen deliberately:

| Order | Category | Why then |
| --- | --- | --- |
| 1 | `iot` | Depends on nothing but the JDK and the node model, so it exercised the whole pipeline with nothing else that could fail |
| 2 | `discord` | The hardest case, done early on purpose — a sibling client package, secrets access, `ResourceRegistry`, both `AutoStartable` and `NodeContentProvider` on one node, dynamic ports, a `saveState` map, and JDA's transitive dependencies. Any gap it exposed was found while only one other library existed to fix up |
| 3 | `camera` | No third-party dependency at all |
| 4 | `web` | jmdns, applying the SLF4J lesson proactively this time |
| 5 | `ml` | DJL's shading and native-library footprint are the messiest, so every earlier lesson was proven first |

## Consequences

An integration ships on its own schedule, and someone can write a node for their
own hardware without forking.

**Old saves keep working.** A graph saved while a node shipped in the app recorded
it by bare class name with no plugin key, and that still resolves, because the
registry indexes a node's simple name alongside its declared `@Node.Type` id. The
Add-Node menu is unchanged too, because a library's `categoryPrefix` reproduces the
old category name.

Three lessons the extractions surfaced, now in
[`../nodes/publishing-a-library.md`](../nodes/publishing-a-library.md):

- **A bundled library's own `slf4j-api` leaks into the shaded jar unless excluded**,
  and the host rejects such a jar. Apply the exclude to *every* coordinate with its
  own path to the module — DJL needed it on three, not one.
- **`@Node.Type` collides with `javafx.scene.Node`.** No in-repo node had ever
  combined the two. Write `javafx.scene.Node` fully qualified.
- **Asset naming is load-bearing for a monorepo release** carrying several jars.

The first-party libraries share one repository. The API will change, and when it
does every library needs rebuilding — one commit and one tag in a monorepo, against
one PR and one tag per repository otherwise. The trade is lockstep versioning, which
is also why a release carries several jars and why the naming convention matters.

The consequence for this repository is that `graph/nodes/` holds **dependency-free
primitives only**, and per-integration reference documentation belongs in
`housegraph-nodes`, not here.

**Reference:** [`../engine/plugin-runtime.md`](../engine/plugin-runtime.md)
