# 0005 — The plugin class loader is parent-first

## Context

Node libraries are loaded from jars at runtime. The conventional choice for a
plugin system is a child-first loader per plugin, giving each one dependency
isolation and a natural identity.

## Decision

**One shared, parent-first `URLClassLoader`** for every installed library.

## Consequences

SLF4J 2 binds once, through `ServiceLoader` against `LoggerFactory`'s own loader —
the app loader, which finds this project's bundled provider and only that. Under
parent-first, a library's `org.slf4j` references resolve to the parent's classes,
so a library embedding something chatty has its logs land in the same `LogManager`
as everything else.

Child-first, or a library bundling its own `org.slf4j`, produces a second binding
routed into a second `LogManager` with no sinks attached. **The logs vanish with no
error anywhere**, which is close to undiagnosable.

The two things a per-library loader would have bought are covered another way:
dependency isolation by shading, and owner identity by the scan-time map that
records which library each discovered type came from.

Because the loader is shared, reloading it re-scans every enabled library, not just
the changed one. So a library change cannot be applied in memory while any
node-library node is on the canvas — a node still bound to the old `Class` object
would be stranded. Such changes are written to disk and take effect on the next
restart.

Install-time validation rejects a jar bundling `housegraph-api`, `org.slf4j`, or an
SLF4J provider. This is not a security control; it turns three baffling runtime
symptoms into one clear message.

**Reference:** [`../engine/plugin-runtime.md`](../engine/plugin-runtime.md)
