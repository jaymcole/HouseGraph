# Shared documentation

Files in this folder are the source of truth for documentation that also
needs to live in HouseGraph's companion repositories —
[`housegraph-nodes`](https://github.com/jaymcole/housegraph-nodes) and
[`housegraph-plugin-template`](https://github.com/jaymcole/housegraph-plugin-template).

On every push to `main` that touches this folder,
[`.github/workflows/sync-docs.yml`](../../.github/workflows/sync-docs.yml)
mirrors it into `docs/shared/` in each companion repo and merges the result
automatically, without manual review on the receiving end. See
[`docs/architecture/doc-sync.md`](../architecture/doc-sync.md) for how the
sync works and what to do if it breaks.

Write anything placed here assuming it will be read from a companion repo,
not just from HouseGraph — avoid relative links or context that only makes
sense in this repo's own doc tree.
