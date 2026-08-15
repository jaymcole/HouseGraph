# `graph/nodes/` — the built-in node library

Full context: [`docs/nodes/`](../../../../../../../../../../docs/nodes/).
Start at the repo-root [`CLAUDE.md`](../../../../../../../../../../CLAUDE.md) if you
haven't.

Every concrete built-in node lives here, one folder per **category**: `constants`,
`control`, `converters`, `debug`, `loader`, `math`, `object`, `resource`,
`viewers`.

**This is dependency-free primitives only.** Anything needing a third-party library
belongs in an out-of-tree node library — see
[`docs/nodes/publishing-a-library.md`](../../../../../../../../../../docs/nodes/publishing-a-library.md).

## Adding a node — there is no registration step

Drop a concrete `BaseNode` subclass under `graph/nodes/<category>/` and it appears
in the Add-Node menu automatically. The folder name is its menu category.

```java
@Display.Name("My Node")
public class MyNode extends BaseNode {
    private final NodeVariable<Float> in  = new NodeVariable<>("In", Float.class);
    private final NodeVariable<Float> out = new NodeVariable<>("Out", Float.class);

    @Override public void process(ProcessContext ctx) { out.setValue(/* work */); }
    @Override public void configureInputs()  { addInput(in); }
    @Override public void configureOutputs() { addOutput(out); }
    @Override public void configureFlowInputs()  { addFlowInput(new FlowPort("", FlowPort.Direction.IN)); }
    @Override public void configureFlowOutputs() { addFlowOutput(new FlowPort("", FlowPort.Direction.OUT)); }
}
```

See `math/AddNode.java` for the real thing, and
[`docs/nodes/first-node.md`](../../../../../../../../../../docs/nodes/first-node.md)
for the walkthrough.

Then, as needed:

- **Branch** → several named OUT `FlowPort`s plus `activate(port)` in `process()`
  (`control/IfNode.java`).
- **Loop** → `runFlowBranchToCompletion(port, seed)` (`control/ForEachNode.java`).
- **Join** → override `isFlowJoin()` (`control/JoinNode.java`).
- **Inline UI** → implement `NodeContentProvider`; push values from `onExecuted()`.
- **Extra config** → override `saveState()`/`loadState()`. Never store a secret
  here — store its `SecretsStore` key and resolve at runtime.
- **Long-lived resource** → register in `ResourceRegistry` from `onActivated()`,
  tear down in `onRemoved()` and `releaseResources()`, open the connection only on
  user action (`resource/EchoResourceNode.java`).
- **Dynamic ports** → react in `onInputEdgeAdded/Removed`, persist the shape in
  `saveState` (`object/ObjectDecomposerNode.java`).

## Rules

- A `NodeVariable` value is saved only if `manuallyEditable && !secret &&
  !transient`. Mark secrets with `markSecret()`, live handles with
  `transientValue()`.
- Mark an input `required()` when a missing value makes the node meaningless.
- Hide a work-in-progress or deprecated node with `@Node.Disabled` — it stays
  loadable for old saves.
- A save identifies a node by its **simple class name** by default, so moving it
  between category folders is safe. **Renaming** the class strands old saves unless
  you pin the old id with `@Node.Type("OldName")` or list it in `aliases`.
- Split teardown: fast and thread-affine in `onRemoved()`, anything that blocks in
  `releaseResources()`.
- Poll `ctx.checkCancelled()` in anything slow.
- Add a test mirroring the existing node tests.

Read
[`docs/nodes/guidelines.md`](../../../../../../../../../../docs/nodes/guidelines.md)
before calling a node done.

**Adding a brand-new category folder? Note it in
[`docs/nodes/README.md`](../../../../../../../../../../docs/nodes/README.md).**
