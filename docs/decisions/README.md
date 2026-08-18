# Decision records

Short records of decisions whose reasoning is not obvious from the code, and which
someone might otherwise reverse by accident.

Reference documentation in [`../engine/`](../engine/) describes the system **as it
is**. These records describe **how it got that way** — the option that was tried and
abandoned, the bug that forced a change, the argument that was withdrawn. That
belongs here rather than inline, so every reader does not pay for it.

Write one when a decision cost real effort to reach and a future contributor might
plausibly undo it. Keep it to roughly 40 lines: Context, Decision, Consequences.
Do not write one for an ordinary implementation choice.

| # | Decision |
| --- | --- |
| [0001](0001-separate-data-and-flow-edges.md) | Data and flow are separate edge types |
| [0002](0002-concurrent-runs-and-execution-policy.md) | Concurrent runs, and per-node execution policy |
| [0003](0003-two-phase-node-teardown.md) | Teardown is two phases with a per-node budget |
| [0004](0004-preserve-unresolvable-nodes.md) | Preserve unresolvable nodes verbatim (save format v2) |
| [0005](0005-parent-first-plugin-classloading.md) | The plugin class loader is parent-first |
| [0006](0006-auto-install-is-daemon-only.md) | Auto-install exists only in the daemon |
| [0007](0007-sync-resets-rather-than-pulls.md) | The git sync resets rather than pulls |
| [0008](0008-integrations-are-out-of-tree.md) | Integrations live in out-of-tree node libraries |
| [0009](0009-supervised-graphs-run-in-a-window.md) | Supervised graphs run in the real windowed app |
| [0010](0010-node-search-is-ranked-not-filtered.md) | Node search is ranked, and does not index ports |
