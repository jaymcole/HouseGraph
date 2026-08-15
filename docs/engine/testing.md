# Testing

Tests use **JUnit 5** and run with `./gradlew test`. They mirror the main source
tree under `src/test/java/...`.

## The headless-testability rule

So much of this codebase is unit-testable without a display because of one design
rule: **pure logic does not import JavaFX.**

- `NodeGraph`, `BaseNode` and the whole `graph/` engine run headless. Tests build a
  throwaway `NodeGraph`, add nodes, wire edges, and drive execution directly.
- `GraphFileIO`'s JSON conversion is separated from the `save`/`load` canvas
  wrappers precisely so the format can be tested without a canvas.
- `ObjectProperties`, `CommandMatcher` and `AppDirectories.resolveRoot` are pure
  and tested as such.
- `plugin/`, `cli/` and `remote/` are headless for the same reason. Nothing worth
  testing may live in a window.

When you extend any of these, keep the JavaFX-free core JavaFX-free. If new logic
must touch the UI, factor the testable part out.

## Patterns

**Engine tests** build a `NodeGraph`, add nodes, set port values, register
`Edge`/`FlowEdge`, and assert on outputs. See `graph/NodeGraphTest.java`. Small
local helpers like `output(node)`, `input(node, name)` and `flowEdge(a, b)` keep
them readable; reuse that style.

**Async execution.** `execute()` returns immediately. Call `graph.awaitIdle()`
before asserting so the run's effects are visible. `beginProcessing()` and
`resolve` are synchronous and need no wait. Because reconvergence is not implicitly
barriered, a test needing a specific order should impose it structurally rather
than relying on timing. See [concurrency.md](concurrency.md).

**Filesystem and secrets tests** use a temp directory through the `housegraph.home`
override or the package-visible `openIn`/`loadFrom`/`AppDirectories(root)`
constructors — never the real user profile. Follow `SecretsStoreTest`,
`AppDirectoriesTest`, `AppPreferencesTest`.

**Node tests** exercise a single node's `process()` or dynamic-port behaviour;
`ObjectDecomposerNodeTest` covers the reflective case. See
[`../nodes/testing-nodes.md`](../nodes/testing-nodes.md).

**Git tests** build a real repository in a `@TempDir` and serve it over a `file://`
URL. `GraphRepositoryTest` does `git init --bare`, commits, pushes, and asserts the
sync picks it up — real git, no network, deterministic. Mocking git would only
assert that the arguments were spelled the way the test expected. Guard the class
with `assumeTrue(GitCommand.isAvailable())`.

**Process tests** inject a fake launcher and a fake clock rather than spawning JVMs
(`SupervisorTest`). What is worth pinning down is the decision-making — when to
restart, how long to back off, when to give up — and none of it needs a real
process. The injected clock lets a sixty-second backoff be asserted in
microseconds.

**Save-format tests need the right registry.** `GraphFileIOTest`'s default registry
is core-only, under which every node is a built-in and no `plugins` row is ever
written. Anything about library-owned nodes must use a registry whose `ScanRoot`
declares a non-core plugin id.

## Expectation for new work

New logic ships with tests mirroring the nearest existing test. A bug fix ships
with a test that would have caught it. Keep tests deterministic: no real network
calls, and no reliance on wall-clock timing beyond `awaitIdle`.

---

**When you change this, update…** this file whenever you change the test
framework, the async-waiting approach, or the conventions for testing storage and
secrets against a temp directory.
