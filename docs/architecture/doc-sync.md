# Cross-repo documentation sync

HouseGraph is the source of truth for documentation shared with its two
companion repositories, [`housegraph-nodes`](https://github.com/jaymcole/housegraph-nodes)
and [`housegraph-plugin-template`](https://github.com/jaymcole/housegraph-plugin-template).
Anything meant to be mirrored into both lives under
[`docs/shared/`](../shared/) here — see that folder's own README for
authoring guidance.

## How it works

[`.github/workflows/sync-docs.yml`](../../.github/workflows/sync-docs.yml)
runs on every push to `main` that touches `docs/shared/**`. For each
companion repo, in parallel, it:

1. Checks out the companion repo using the `DOCS_SYNC_PAT` secret — a
   fine-grained GitHub PAT scoped to just `housegraph-nodes` and
   `housegraph-plugin-template`, with `Contents: read & write` and
   `Pull requests: read & write` permissions. The default `GITHUB_TOKEN`
   only has permissions inside the repo the workflow runs in, so writing to
   a different repository requires a PAT.
2. Overwrites the companion repo's `docs/shared/` with HouseGraph's copy,
   verbatim (same relative path in every repo).
3. If anything changed, commits to a `docs-sync` branch, opens a pull
   request, and immediately squash-merges it — no human clicks "merge".

The change still goes through a branch and PR (rather than a direct push to
`main`) purely so there's an inspectable commit and PR reference for every
sync — auto-merging skips the review, not the audit trail. Because the
merge happens immediately and unconditionally, a companion repo with branch
protection that *requires* review approval will block the sync; keep
`main` on `housegraph-nodes` and `housegraph-plugin-template` free of
required-review rules, or the workflow step will fail at the merge step.

## Changing what gets synced

Add or edit files under `docs/shared/` in HouseGraph and merge to `main`.
Nothing needs to be registered — the workflow mirrors whatever is present
in the folder at the time of the push.

## Rotating the PAT

`DOCS_SYNC_PAT` is stored as an Actions secret on this repo (`housegraph`
only — the companion repos don't need it, since they're written to, not
from). If it expires or is rotated, generate a new fine-grained PAT with the
same scopes (GitHub → Settings → Developer settings → Fine-grained tokens)
and update the secret; nothing else changes.

---
**When you change this:** update this file if the trigger, target path, PAT
scope, or merge behavior changes, and update the doc-mandate table in the
root `CLAUDE.md` if the change affects what contributors need to know.
