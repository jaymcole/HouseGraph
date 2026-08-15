# Your first node

A node that multiplies two floats, complete. Compare with
`graph/nodes/math/AddNode.java`.

```java
package io.github.jaymcole.housegraph.graph.nodes.math;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;

@Display.Name("Multiply")
public class MultiplyNode extends BaseNode {

    private final NodeVariable<Float> a = new NodeVariable<>("A", Float.class);
    private final NodeVariable<Float> b = new NodeVariable<>("B", Float.class);
    private final NodeVariable<Float> product = new NodeVariable<>("Product", Float.class);

    @Override public void process(ProcessContext ctx) {
        product.setValue(ctx.get(a, 0f) * ctx.get(b, 0f));
    }

    @Override public void configureInputs()      { addInput(a); addInput(b); }
    @Override public void configureOutputs()     { addOutput(product); }
    @Override public void configureFlowInputs()  { addFlowInput(new FlowPort("", FlowPort.Direction.IN)); }
    @Override public void configureFlowOutputs() { addFlowOutput(new FlowPort("", FlowPort.Direction.OUT)); }
}
```

Drop that under `graph/nodes/math/` and it appears in the Add-Node menu under
**math**. Nothing to register.

## What each piece does

**`@Display.Name`** sets the menu and title label. Without it, `getName()` falls
back to the simple class name.

**The four `configure*` hooks** declare ports. They run lazily on first port
access, not from the constructor, so your field initializers have already run by
then.

**`process(ProcessContext ctx)`** does the work. By the time it is called, every
input has been resolved through its incoming data edge.

- `ctx.get(input, fallback)` is the null-safe read. Use it rather than
  `input.getValue()` unless you genuinely want to handle null yourself.
- `ctx.checkCancelled()` lets a long-running or looping `process()` bail out when
  its run is superseded or its timeout fires. Poll it periodically in anything
  expensive. A node that ignores `ctx` still works.

**The flow ports** make the node triggerable and let control continue past it. A
node with no flow input cannot be triggered along a flow edge, but can still be
pulled as a data dependency — which is exactly what a constant or a pure
calculation wants.

## Checklist

1. Extend `BaseNode`; put it under `graph/nodes/<category>/`.
2. Declare ports in the `configure*` hooks; do the work in `process()`.
3. Add `@Display.Name("…")`.
4. Read [guidelines.md](guidelines.md) before you call it done.
5. Add a test — see [testing-nodes.md](testing-nodes.md).

## Then, as needed

| Want | See |
| --- | --- |
| Mark an input required, secret, or transient | [ports-and-values.md](ports-and-values.md) |
| Fire one of several outputs | [flow-control.md](flow-control.md) |
| Buttons, labels or a preview inside the node | [inline-ui.md](inline-ui.md) |
| Remember a dropdown choice across saves | [state-and-startup.md](state-and-startup.md) |
| Hold a connection, server or bot open | [long-lived-resources.md](long-lived-resources.md) |
| Ports that depend on what is wired in | [dynamic-ports.md](dynamic-ports.md) |
| Cap concurrency or add a timeout | [execution-tuning.md](execution-tuning.md) |

---

**When you change this, update…** this file whenever the minimal node shape
changes — the `configure*` hooks, the `process` signature, or the discovery rule.
