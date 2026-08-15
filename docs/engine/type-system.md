# Data types and conversion

A data anchor's type is a `Class<?>` on the `NodeVariable`. That type governs which
outputs may feed it.

## When an edge is allowed

An `Edge` may join an output to an input when the input type is **assignable from**
the output type — an exact match, a subtype, or an `Object` input that accepts
anything — **or** when a converter is registered for the `(output, input)` pair in
`TypeConverters`.

`NodeGraph.attachEdge` is the authoritative gate and throws on an incompatible
pair. `GraphCanvas.isValidConnection` mirrors it for the drag-time highlight. Both
call `TypeConverters.isCompatible(from, to)`.

## One data edge per input

`attachEdge` also enforces cardinality: an input `NodeVariable` is fed by **at most
one** data edge, so a node pulls each input through a single unambiguous source.
Two edges into one input would make `propagateValue`'s last-writer-wins order
nondeterministic.

A second edge into an occupied input throws `IllegalStateException`. Rewiring means
*replace*: remove the existing edge, then add the new one. `GraphCanvas.createEdge`
does this before registering, and the load path drops any extra edge per-edge
rather than stacking them. Re-registering an identical edge is idempotent.

Outputs are unconstrained — one output may fan out to many inputs.

## `TypeConverters`

`TypeConverters` lives in `graph/`, so it stays headless. It ships a built-in
matrix interconverting `Integer`, `Double`, `Float` and `Boolean` in both
directions.

Additional converters register at runtime via
`TypeConverters.register(from, to, safety, fn)` — the extension point for node
libraries. When a value propagates through `NodeGraph.propagateValue`, the
registered converter coerces it at the handoff. When no converter applies, the raw
value passes through unchanged.

These implicit converters are distinct from the explicit converter **nodes** in
`graph/nodes/converters/`, which remain for visible, first-class conversions and
for targets the matrix does not cover, such as `*` → `String`.

## Conversion safety

Every converter carries a `ConversionSafety` level, and
`TypeConverters.classify(from, to)` reports it for a pair.

| Level | Meaning | Examples |
| --- | --- | --- |
| `SAFE` | assignable, or lossless/widening | `Integer` → `Float`/`Double`, `Boolean` → number |
| `CAUTIOUS` | predictable loss | `Double`/`Float` → `Integer` truncation, `Double` → `Float` |
| `RISKY` | drastic loss | number → `Boolean`, collapsing non-zero to `true` |
| `INCOMPATIBLE` | no path | — |

**The level is advisory for connecting.** Both gates allow anything that is not
`INCOMPATIBLE`; `isCompatible` is exactly `classify != INCOMPATIBLE`. Its purpose
is feedback: the canvas colours a candidate anchor green, yellow, orange or red
from `classify` while an edge is dragged. See [ui-layer.md](ui-layer.md).

## Making a type editable

A `NodeVariable` gets an inline text field only if it is `manuallyEditable` **and**
its type is registered in `sdk.ValueEditors`, which maps a type to a parse/format
pair. Registered in this repository: `Float`, `String`, `Integer`.

In this repository, add one line to the `ValueEditors` static block. A node library
calls `ValueEditors.register(...)` directly, which is why the backing map is a
`ConcurrentHashMap` and why the class sits in the published API rather than in
`ui/editor`. It is the direct counterpart of `TypeConverters`.

One subtlety: node discovery loads classes with `initialize = false`, so a node's
static initializer runs at first *instantiation*, not at scan time. A type
registered from a node's static block becomes editable only once one of those nodes
exists — soon enough in practice, since there is nothing to edit before then.

---

**When you change this, update…** this file whenever you change edge
compatibility, the converter matrix, the safety classification, or the set of
registered value editors.
