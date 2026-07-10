# `graph/nodes/` — the node library

Full context: [`docs/architecture/nodes.md`](../../../../../../../../../docs/architecture/nodes.md).
Start at the repo-root [`CLAUDE.md`](../../../../../../../../../CLAUDE.md) if you haven't.

Every concrete node lives here, one folder per **category** (`camera`,
`constants`, `control`, `converters`, `debug`, `discord`, `iot`, `loader`,
`math`, `object`, `resource`, `viewers`, `web`).

## Adding a node — there is no registration step

Drop a concrete `BaseNode` subclass under `graph/nodes/<category>/` and it appears
in the Add-Node menu automatically (`NodeRegistry` scans the classpath). The
folder name is its menu category.

Skeleton (see `math/AddNode.java` for the real thing):

```java
@Display.Name("My Node")
public class MyNode extends BaseNode {
    private final NodeVariable<Float> in  = new NodeVariable<>("In", Float.class);
    private final NodeVariable<Float> out = new NodeVariable<>("Out", Float.class);

    @Override public void process() { out.setValue(/* work */); }
    @Override public void configureInputs()  { addInput(in); }
    @Override public void configureOutputs() { addOutput(out); }
    @Override public void configureFlowInputs()  { addFlowInput(new FlowPort("", FlowPort.Direction.IN)); }
    @Override public void configureFlowOutputs() { addFlowOutput(new FlowPort("", FlowPort.Direction.OUT)); }
}
```

Then, as needed:

- **Branch** → add several named OUT `FlowPort`s and `activate(port)` inside
  `process()` (see `control/IfNode.java`).
- **Inline UI** → implement `NodeContentProvider`; push values from `onExecuted()`.
- **Extra config** → override `saveState()`/`loadState()`. Never store a secret
  here — store its `SecretsStore` key and resolve at runtime.
- **Long-lived resource** → register in `ResourceRegistry` from `onActivated()`,
  tear down in `onRemoved()`, open the connection only on user action
  (see `discord/DiscordBotNode.java`).
- **Dynamic ports** → react in `onInputEdgeAdded/Removed`, persist the shape in
  `saveState` (see `object/ObjectDecomposerNode.java`).

## Rules

- A `NodeVariable` value is only saved if `manuallyEditable && !secret &&
  !transient`. Mark secrets with `markSecret()`, live handles with
  `transientValue()`.
- Hide a work-in-progress or deprecated node from the menu with `@Node.Disabled`
  (it stays loadable for old saves).
- Add a test mirroring the existing node tests.

**Adding a brand-new category folder? Note it in
[`docs/architecture/nodes.md`](../../../../../../../../../docs/architecture/nodes.md).**
