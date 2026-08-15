# 0001 — Data and flow are separate edge types

## Context

A node graph needs to express two different things: which value feeds which input,
and in what order nodes run. Many graph editors fold both into one connection type,
with a value that control-only connections leave empty.

## Decision

Two distinct types, moved in opposite directions.

- `NodeVariable<T>` joined by `Edge` carries a typed value, **pulled** on demand: a
  node resolves its inputs when it needs them.
- `FlowPort` joined by `FlowEdge` carries execution order and no value, **pushed**:
  a trigger cascades to downstream nodes.

Neither folds into the other.

## Consequences

Flow ports carry no dead value or type machinery, and data ports carry no
control-only special cases. The type system in `TypeConverters` applies to exactly
one of them, and the re-entrancy machinery to the other.

Pull semantics mean a data-only subgraph never needs a trigger, and every resolve
runs in a fresh context, so a stale cached value cannot be served.

Push semantics mean order is explicit on the canvas rather than inferred from data
dependencies, which is what makes side-effecting nodes — send a message, turn on a
light — orderable at all.

The cost is two visually distinct connection systems for a user to learn, and two
sets of anchors on every node.

**Reference:** [`../engine/execution-model.md`](../engine/execution-model.md)
