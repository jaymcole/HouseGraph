# Core engine

Technical reference for HouseGraph's engine, runtime and host application: how a
graph executes, how state is persisted, how node libraries are loaded, and what
runs unattended.

This section describes the system as built, and the reasoning behind each design
decision. It is written for someone changing the engine. For writing a node, see
[`../nodes/`](../nodes/); for setting up and running HouseGraph, see
[`../guides/`](../guides/).

## Invariants

These hold today and are expected to keep holding. A change that breaks one needs
a deliberate decision, not an incidental commit.

1. **Data and flow stay separate.** `NodeVariable`/`Edge` carry a typed value,
   pulled on demand. `FlowPort`/`FlowEdge` carry execution order and no value,
   pushed when a trigger fires. Neither folds into the other.
2. **`graph/` never imports JavaFX.** Engine-to-UI notification goes through the
   injectable callback executor and `GraphExecutionListener`. This is what keeps
   the engine unit-testable without a display.
3. **Nodes never persist computed or secret values.** Only manually-authored,
   non-secret, non-transient values reach a save file
   (`NodeVariable.isPersistentValue`).
4. **Credentials live only in `SecretsStore`, encrypted.** A node persists a
   reference to a secret, never the value.
5. **All on-disk paths go through `AppDirectories`.** No hardcoded home
   directories, no OS-specific locations elsewhere.
6. **Pure logic stays headless.** `NodeGraph`, `GraphFileIO`'s JSON conversion and
   `ObjectProperties` avoid JavaFX so they can be tested directly.

## API stability

`housegraph-api` is compiled against by the libraries in
[`housegraph-nodes`](https://github.com/jaymcole/housegraph-nodes) and by anything
built from the
[plugin template](https://github.com/jaymcole/housegraph-plugin-template).

**The API is not stable yet.** Breaking changes are acceptable. They mean
rebuilding those repositories, so make the change there in the same pass. Prefer
adding a concrete hook with a default over a new abstract method, since that
avoids the rebuild — a preference, not a guarantee.

## Contents

| Doc | Covers |
| --- | --- |
| [architecture.md](architecture.md) | modules, layering, dependency direction, application lifecycle |
| [execution-model.md](execution-model.md) | resolve vs. execute, run lifecycle, node status, `ProcessContext` |
| [concurrency.md](concurrency.md) | virtual threads, `ExecutionContext`, locking, fan-out and joins |
| [execution-policy.md](execution-policy.md) | `DROP`/`RESTART`/`QUEUE`/`PARALLEL` at two scopes |
| [loops.md](loops.md) | seeded sub-runs for for-each bodies |
| [type-system.md](type-system.md) | anchor types, `TypeConverters`, conversion safety |
| [node-lifecycle.md](node-lifecycle.md) | lifecycle hooks, two-phase teardown, shutdown budgets |
| [save-format.md](save-format.md) | the graph JSON format and its compatibility rules |
| [ui-layer.md](ui-layer.md) | canvas, views, undo, the FX-thread rule |
| [node-search.md](node-search.md) | what is indexed, the scoring model, query syntax, `NodeKind` |
| [plugin-runtime.md](plugin-runtime.md) | module split, class loading, catalog, discovery |
| [remote-runtime.md](remote-runtime.md) | git sync, process supervision, exit codes, shutdown |
| [storage.md](storage.md) | on-disk layout, secrets, preferences |
| [document-store.md](document-store.md) | the shared JSON document store behind data-store nodes |
| [logging.md](logging.md) | levels, sinks, the SLF4J bridge |
| [security-model.md](security-model.md) | trust boundaries and what is not defended |
| [testing.md](testing.md) | conventions and the headless-testability rule |

Decisions with a history worth recording live in
[`../decisions/`](../decisions/).
