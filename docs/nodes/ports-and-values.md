# Ports and values

A `BaseNode` declares four kinds of port, each through a `configure*` hook.

| Hook | Adds | Default |
| --- | --- | --- |
| `configureInputs()` | `addInput(v)` — `NodeVariable<T>` slots read during `process()` | none |
| `configureOutputs()` | `addOutput(v)` — `NodeVariable<T>` slots written during `process()` | none |
| `configureFlowInputs()` | `addFlowInput(port)` — where control can enter | none |
| `configureFlowOutputs()` | `addFlowOutput(port)` — where control leaves | none |

The hooks run lazily on first port access, not from the constructor, so subclass
field initializers have already run. `reconfigure()` and `rebuildPorts()` rebuild
the lists for nodes whose ports depend on settings — see
[dynamic-ports.md](dynamic-ports.md).

**Flow inputs are optional.** A node with none cannot be triggered along a flow
edge but can still be pulled as a data dependency, which is what a constant or a
pure calculation wants. Most executable nodes add one unnamed IN port and one
unnamed OUT port.

## `NodeVariable<T>`

A typed slot with a `name`, a stable `id`, a `type`, and a `manuallyEditable` flag.

```java
private final NodeVariable<String> host =
        new NodeVariable<>("Host", String.class).required();
```

`markSecret()`, `transientValue()` and `required()` are fluent, for field
initialization.

## Persistence rules

`isPersistentValue()` is `manuallyEditable && !secret && !transient`. **Only
manually-authored, non-secret, non-transient values are saved.** Computed outputs
are left null and recomputed on load.

| Marker | Means | Use for |
| --- | --- | --- |
| *(none)* | ordinary value; saved if manually authored | a typed host name, an interval |
| `markSecret()` | never written to disk | a resolved token or password |
| `transientValue()` | a live runtime handle, meaningful only within one run | a Discord reply object, an open stream |

For a credential, persist the *reference* — the `SecretsStore` key, usually in
`saveState` — and resolve the value at runtime. See
[state-and-startup.md](state-and-startup.md).

This rule also governs copy/paste: `NodeRegistry.duplicate` carries across only
persistent values, so a pasted node never inherits a computed output or a secret
that was resolved off an edge.

## Required inputs

`required()` declares that an input must have a value source — an incoming data
edge **or** a non-null manually-authored value — or the node is **misconfigured**.
Inputs are optional by default.

`BaseNode.getUnsatisfiedRequiredInputs()` and `isMisconfigured()` evaluate this
headlessly against the node's current wiring and values. The UI renders the result
as a red node border, red input-port anchors, and a tooltip naming the offenders.

The author sets the default with `required()`. **The user can override it
per-input** from the node's right-click *Required inputs* menu, and that choice is
persisted. Do not rely on `required()` being unchanged at runtime.

Mark an input required when a missing value makes the node meaningless. Leave it
optional when there is a sensible default.

## Types and what may connect

A `NodeVariable`'s `type` governs which outputs may feed it. An edge is allowed
when the input type is assignable from the output type, **or** a converter is
registered for the pair in `TypeConverters` — so an `Integer`, `Double` or
`Boolean` output can feed a `Float` input, coerced transparently at handoff.

An input takes **at most one** data edge. Outputs may fan out freely.

Register your own converter with `TypeConverters.register(from, to, safety, fn)`.
This is separate from the explicit converter **nodes** under
`graph/nodes/converters/`, which remain for visible conversions and for targets the
built-in matrix does not cover, such as `*` → `String`.

Full detail, including the safety levels the canvas colours anchors by, is in
[`../engine/type-system.md`](../engine/type-system.md).

## Making a custom type editable

A `NodeVariable` gets an inline text field only if it is `manuallyEditable` **and**
its type is registered in `sdk.ValueEditors`. Registered by default: `Float`,
`String`, `Integer`.

In this repository, add one line to the `ValueEditors` static block. An out-of-tree
library calls `ValueEditors.register(type, parser, formatter)` directly, typically
from a static block on one of its nodes. See [inline-ui.md](inline-ui.md).

---

**When you change this, update…** this file whenever you change the port model,
the persistence markers, the required-input contract, or the editable-type
mechanism.
