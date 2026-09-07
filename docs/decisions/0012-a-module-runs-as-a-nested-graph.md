# 0012 — A module runs as a nested graph, not flattened into its consumer

## Context

A `ModuleNode` references a saved graph. Making it run means deciding where that
graph's nodes live while they run. Two shapes were available.

**Flattening**: splice the module's nodes and edges into the consuming `NodeGraph`
at load, rewiring its boundary markers into ordinary edges. One graph, one run, no
new execution concept — a run cascades straight through the module and out again,
and every existing mechanism (dedup, joins, the step delay, `awaitIdle`) applies
unchanged because nothing has changed.

**Nesting**: give the module a `NodeGraph` of its own, and have `ModuleNode.process()`
drive one run of it and block until it settles.

Flattening is genuinely simpler until the module has to be more than a macro. Two
things broke it.

## Decision

**Nest.** A `ModuleInstance` is a `NodeGraph`, built once per referencing node and
reused, and `NodeGraph.runToCompletion` is the new primitive that drives one run of
it and waits.

Two things settled it.

**A module has state, and flattening has nowhere to put it.** A module holding a
resource node or a repeating trigger is a module worth having, and both are alive
between invocations. Flattened, two references to one module would be two copies of
its nodes with no boundary between them, in a graph the user did not author and
cannot see — every save, every validation, every node count in the consuming graph
would include nodes belonging to something else.

**A module's edits must reach its consumers.** Referenced by id, a module is expected
to change independently of the graphs that use it. Flattening bakes a copy in at
load; a nested graph is rebuilt from the file, so a consumer picks the change up by
being re-opened rather than by being edited.

## Consequences

- A new execution concept — a run that drives another graph's run — with its own
  primitive, its own seam for reading a run's values (`RunScope`), and cancellation
  that crosses the boundary. That is the cost, and it is documented in
  [`../engine/execution-model.md`](../engine/execution-model.md#driving-another-graphs-run).
- Nothing crosses a graph boundary by accident: the callback executor, the step delay
  and the release timeout are all per-graph, and each had to be passed on explicitly.
- Nesting has depth, so it needs a cap (`ModuleInstance.MAX_DEPTH`) that flattening
  would not have.
- `ResourceRegistry` names stay app-wide, so a module publishing one is still usable
  only once — nesting bounds the *values*, not the registry. See
  [`../nodes/long-lived-resources.md`](../nodes/long-lived-resources.md).
