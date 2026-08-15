# Testing nodes

New nodes ship with a test. A bug fix ships with a test that would have caught it.

Tests use JUnit 5 and run with `./gradlew test`.

## Testing `process()` directly

The simplest case needs no graph. `ProcessContext.uncancelled()` is the factory for
invoking a node's `process()` outside the engine, where there is nothing to cancel.

```java
@Test
void multipliesItsInputs() {
    MultiplyNode node = new MultiplyNode();
    input(node, "A").setValue(3f);
    input(node, "B").setValue(4f);

    node.process(ProcessContext.uncancelled());

    assertEquals(12f, output(node, "Product").getValue());
}
```

## Testing in a graph

When wiring matters — a branch, a join, a data dependency — build a throwaway
`NodeGraph`, add nodes, register edges, and drive execution. See
`graph/NodeGraphTest.java`, and reuse its small local helpers (`output(node)`,
`input(node, name)`, `flowEdge(a, b)`) rather than inventing new ones.

**`execute()` is asynchronous.** It returns immediately and the run proceeds on
background threads. Call `graph.awaitIdle()` before asserting.

```java
graph.execute(trigger);
graph.awaitIdle();
assertEquals(NodeProcessingStatus.SUCCESS, downstream.getStatus());
```

`beginProcessing()` and `resolve` are synchronous and need no wait.

**Do not assert on the order of sibling branches.** A fan-out does not join, so
branches run concurrently. If a test needs an order, impose it structurally with a
flow edge or a join node.

## Testing a node's own UI

Do not. Factor the logic out of `createNodeContent()` into a plain method or a
separate class and test that. This repository has no way to test a window, which is
why `plugin/`, `cli/` and `remote/` are headless too.

## Nodes with state

`saveState()`/`loadState()` round-tripping is worth a direct test, especially for a
[dynamic-port node](dynamic-ports.md) whose port shape is reconstructed from state.
`ObjectDecomposerNodeTest` covers the reflective case.

## Nodes that touch disk

Point `AppDirectories` at a temp directory through the `housegraph.home` override,
or use the package-visible constructors. Never touch the real user profile. Follow
`SecretsStoreTest` and `AppDirectoriesTest`.

## Keep it deterministic

No real network calls. No reliance on wall-clock timing beyond `awaitIdle`. If your
node has a clock or a launcher, inject it — `SupervisorTest` asserts a sixty-second
backoff in microseconds with an injected clock.

---

**When you change this, update…** this file whenever the async-waiting approach or
the `ProcessContext` test factory changes. General conventions are in
[`../engine/testing.md`](../engine/testing.md).
