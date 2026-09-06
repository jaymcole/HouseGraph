# Inline node UI

A node can embed its own JavaFX content at the bottom of its view — buttons, a
status label, an image preview — by implementing `sdk.NodeContentProvider`.

```java
public class MyNode extends BaseNode implements NodeContentProvider {

    private Label status;

    @Override public javafx.scene.Node createNodeContent() {
        status = new Label("Idle");
        Button go = new Button("Start");
        go.setOnAction(e -> startOffThread());
        return new VBox(4, go, status);
    }

    @Override public void onExecuted() {
        status.setText("Ran at " + LocalTime.now());
    }
}
```

`createNodeContent()` is called **once**, when the view is built. Keep references
to anything you need to update later.

Implementing the interface is the entire opt-in. The host dispatches with
`instanceof`; there is nothing to register.

## Threading

Both `createNodeContent()` and `onExecuted()` arrive **on the FX thread** —
`onExecuted` is dispatched through `NodeGraph`'s callback executor, which the app
sets to `Platform::runLater`. So your own UI code needs no `Platform.runLater`.

**Work you start yourself is a different matter.** A button handler runs on the FX
thread, so anything that blocks — a network call, a socket bind, a process spawn —
must go to a worker, with `Platform.runLater` for the UI update afterwards:

```java
private void startOffThread() {
    Thread.ofVirtual().start(() -> {
        try {
            resource.start();
            Platform.runLater(() -> status.setText("Running"));
        } catch (Exception e) {
            Platform.runLater(() -> status.setText("Failed: " + e.getMessage()));
        }
    });
}
```

## Pushing computed values into your UI

Override `onExecuted()`, which runs right after `process()` whether it succeeded or
failed. Do not update UI from inside `process()` — that runs on an engine thread.

## Do not import `javafx.scene.Node`

If your node also uses `@Node.Type` from
`io.github.jaymcole.housegraph.annotations`, the two `Node` types collide. Write
`javafx.scene.Node` fully qualified at each use, as the example above does. The
template's `HelloWorldNode` follows the same pattern.

## Your node must work without its UI

`createNodeContent()` only runs when something draws the node, so every field it
assigns is null the rest of the time: a graph opened by a headless loader, a node
torn down before it was rendered, and — once graphs can be used from inside other
graphs — every node in the interior of such a graph, in an app that is fully
windowed. **Having a view is a fact about one node, not about the process**, which
is why nothing here consults `sdk.RuntimeMode.isDaemon()`.

Three rules keep a node working either way.

**Own your running state.** A running flag is a field of the node. "Running means
the timer object is non-null" makes the lifecycle depend on the UI that built the
timer, and `saveState()` then reads a control.

```java
private volatile boolean running;

@Override public Map<String, String> saveState() {
    return running ? Map.of("running", "true") : Map.of();
}
```

**Drive clocks with `sdk.NodeTimer`, not `javafx.animation.Timeline`.** A
`Timeline` only ticks while the toolkit is running. `NodeTimer` ticks wherever the
node is, on a shared daemon scheduler, one virtual thread per tick, skipping a tick
whose predecessor has not finished. Stop it in `onRemoved()` — cancelling is
immediate and never waits on a tick in flight, so it belongs in the fast half of
teardown ([node-lifecycle.md](../engine/node-lifecycle.md)).

**Route every control update through `present(...)`.** `BaseNode.present(Runnable)`
runs the block through the sink the node's view installed, and discards it when
there is no view. In the desktop app that sink runs the block inline when the caller
is already on the FX thread and marshals it there otherwise — which is what makes a
`NodeTimer` tick safe to show something.

```java
private void start() {
    running = true;
    clock.start(1000, this::tick);
    present(() -> {
        startButton.setDisable(true);
        status.setText("Running");
    });
}
```

Read the node's state *inside* the block: it may run later than the call.

`BaseNode.hasView()` answers the question directly, for the rarer case of skipping
work that exists only to feed a control — rendering a thumbnail, formatting a long
report. Ordinary control updates need only `present(...)`.

A node that follows all three starts, runs, stops and resumes with no view. One that
does not still works in a window and throws the moment it is loaded without one.
Supervised graphs run in a real window for this reason among others; see
[`../engine/remote-runtime.md`](../engine/remote-runtime.md#why-graphs-still-run-in-a-window).

## Registering a custom editable type

`sdk.ValueEditors` maps a type to a parse/format pair, and a `NodeVariable` gets an
inline text field only if its type is registered.

```java
static {
    ValueEditors.register(Duration.class, Duration::parse, Duration::toString);
}
```

Node discovery loads classes with `initialize = false`, so a static block runs at
first **instantiation**, not at scan time. A type registered this way becomes
editable once one of those nodes exists — soon enough in practice, since there is
nothing to edit before then.

In this repository, add the line to the `ValueEditors` static block instead.

## Why this interface is in the API module

`NodeContentProvider` returns a live `javafx.scene.Node`, which is the sole reason
`housegraph-api` depends on JavaFX at all. The dependency is declared on the `api`
configuration so node authors get `javafx.scene.Node` on their compile classpath —
though you still apply the JavaFX Gradle plugin yourself; see
[publishing-a-library.md](publishing-a-library.md).

---

**When you change this, update…** this file whenever you change
`NodeContentProvider`, `NodePresentation`, `NodeTimer`, `ValueEditors`, or the thread
on which any of them is dispatched. The consuming side is in
[`../engine/ui-layer.md`](../engine/ui-layer.md); the cross-repo copy of the
no-view rules is [`../shared/node-library-rules.md`](../shared/node-library-rules.md).
