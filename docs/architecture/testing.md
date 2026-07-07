# Testing

Tests use **JUnit 5** (`org.junit.jupiter`) and run with `./gradlew test`. They
mirror the main source tree under `src/test/java/...`.

## The headless-testability principle

The reason so much of this codebase is unit-testable without a display is a
deliberate design rule: **pure logic does not import JavaFX.**

- `NodeGraph`, `BaseNode`, and the whole `graph/` engine run headless. Tests build
  a throwaway `NodeGraph`, add nodes, wire edges, and drive execution directly.
- `GraphFileIO`'s JSON conversion (`toJson`/`fromJson`) is separated from the
  `save`/`load` canvas wrappers precisely so the format can be tested without a
  canvas.
- `ObjectProperties`, `CommandMatcher`, `AppDirectories.resolveRoot`, and the
  camera value/parse logic are pure and tested as such.

When you extend any of these, keep the JavaFX-free core JavaFX-free, and add tests
against it. If new logic *must* touch the UI, factor the testable part out.

## Patterns to follow

- **Engine tests** build a `NodeGraph`, add nodes, set values on ports, register
  `Edge`/`FlowEdge`, and assert on outputs. See `graph/NodeGraphTest.java`. Small
  local helpers like `output(node)` / `input(node, name)` / `flowEdge(a, b)` keep
  the tests readable — reuse that style.
- **Async execution:** `execute()` runs on a background thread and returns
  immediately. Call `graph.awaitIdle()` before asserting, so the background pass's
  effects are visible. (`beginProcessing()`/`resolve` are synchronous — no
  `awaitIdle` needed.)
- **Filesystem/secrets tests** use a temp directory via the `housegraph.home`
  override or the package-visible `openIn`/`loadFrom`/`AppDirectories(root)`
  constructors — never the real user profile. Follow `SecretsStoreTest`,
  `AppDirectoriesTest`, `AppPreferencesTest`.
- **Node tests** exercise a single node's `process()`/dynamic-port behavior;
  `ObjectDecomposerNodeTest` covers the reflective/dynamic case.

## Expectation for new work

New logic ships with tests that mirror the nearest existing test. A bug fix ships
with a test that would have caught it. Keep tests deterministic — no real network
calls, no reliance on wall-clock timing beyond `awaitIdle`.

---

**When you change this, update…** this file whenever you change the test
framework, the async-waiting approach (`awaitIdle`), or the conventions for
testing storage/secrets against a temp directory.
