# Cross-repo documentation sync

HouseGraph is the source of truth for documentation shared with
[`housegraph-nodes`](https://github.com/jaymcole/housegraph-nodes) and
[`housegraph-plugin-template`](https://github.com/jaymcole/housegraph-plugin-template).
Anything meant to be mirrored into both lives under [`shared/`](shared/).

> **`shared/` currently holds no synced content.** The obvious candidates are the
> node-authoring pages — [`nodes/guidelines.md`](nodes/guidelines.md) and
> [`nodes/publishing-a-library.md`](nodes/publishing-a-library.md) — which the
> companion repositories both need. Moving them here means rewriting their relative
> links, since a synced file is read from a repository with a different doc tree.

## How it works

[`.github/workflows/sync-docs.yml`](../.github/workflows/sync-docs.yml) runs on
every push to `main` that touches `docs/shared/**`. For each companion repository,
in parallel, it:

1. Checks the repository out using the `DOCS_SYNC_PAT` secret — a fine-grained
   GitHub PAT scoped to just those two repositories, with `Contents: read & write`
   and `Pull requests: read & write`. The default `GITHUB_TOKEN` only has
   permissions inside the repository the workflow runs in, so writing elsewhere
   requires a PAT.
2. Overwrites the companion's `docs/shared/` with this copy, verbatim, at the same
   relative path.
3. If anything changed, commits to a `docs-sync` branch, opens a pull request, and
   squash-merges it immediately.

The branch and PR exist so there is an inspectable commit and PR reference for
every sync. Auto-merging skips the review, not the audit trail.

Because the merge is immediate and unconditional, **a companion repository with
branch protection requiring review approval will block the sync.** Keep `main` on
both free of required-review rules, or the merge step fails.

## Changing what gets synced

Add or edit files under `shared/` and merge to `main`. Nothing needs registering —
the workflow mirrors whatever is present at the time of the push.

Write anything placed there assuming it will be read from a companion repository:
no relative links into this repository's doc tree, and no context that only makes
sense here.

## Rotating the PAT

`DOCS_SYNC_PAT` is an Actions secret on this repository only; the companions are
written to, not from. If it expires, generate a new fine-grained PAT with the same
scopes and update the secret. Nothing else changes.

---

**When you change this, update…** this file if the trigger, target path, PAT scope
or merge behaviour changes.
