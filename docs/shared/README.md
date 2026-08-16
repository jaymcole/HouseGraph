# Shared documentation

Documentation that has to be identical across the three HouseGraph repositories —
[HouseGraph](https://github.com/jaymcole/HouseGraph),
[housegraph-nodes](https://github.com/jaymcole/housegraph-nodes) and
[housegraph-plugin-template](https://github.com/jaymcole/housegraph-plugin-template).

| File | Holds |
| --- | --- |
| [node-library-rules.md](node-library-rules.md) | The build rules, API surface, node-design guidance and trust note every node library author needs |

**HouseGraph is the source of truth.** These files are authored in
[`docs/shared/`](https://github.com/jaymcole/HouseGraph/tree/main/docs/shared)
there and mirrored into the other two automatically on every push. Editing a copy
in a companion repository is pointless — the next sync overwrites it.

Anything placed here is read from all three repositories, so it uses absolute
`https://github.com/jaymcole/...` URLs rather than relative links, and avoids
context that only makes sense in one of them. The sync replaces the whole folder,
so a file removed at the source is removed everywhere.

The mechanism is described in
[`docs/doc-sync.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/doc-sync.md).
