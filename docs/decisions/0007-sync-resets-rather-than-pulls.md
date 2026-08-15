# 0007 — The git sync resets rather than pulls

## Context

The daemon keeps a local copy of each tracked graphs repository. The obvious way to
update it is `git pull`.

## Decision

Treat the local copy as a **mirror, never a working copy**:

| Step | Command |
| --- | --- |
| poll | `git ls-remote <url> refs/heads/<branch>` |
| first sync | `git clone --depth 1 --branch <branch>` |
| update | `git fetch --depth 1` → `git reset --hard FETCH_HEAD` → `git clean -fd` |

## Consequences

**A pull can conflict, and a conflict on an unattended machine is a silent hang**
with nobody to resolve it. The daemon would sit there believing it was up to date.
Reset cannot fail that way.

The cost is that anything edited by hand inside the clone is discarded. That is the
correct trade for a directory whose entire purpose is to reflect what was pushed —
but it has to be documented for operators, because the failure is silent from their
side too.

`clean -fd` is not optional. A reset alone leaves untracked files behind, so a
graph deleted upstream would keep running.

Asking the remote first, and touching the mirror only when the answer differs,
means the steady state costs one `ls-remote` and no disk writes.

`ls-remote` speaks the **git protocol**, not `api.github.com`, so the
60-requests-per-hour REST budget that forces `GitHubReleases` to check only on user
action does not apply. That asymmetry is why a once-a-minute poll is reasonable here
and would not be for update checks.

`config/remote-state.json` records the last commit deployed per repository, so a
rebooted machine does not treat everything as changed and bounce every graph at
exactly the moment things should come up quietly.

**Reference:** [`../engine/remote-runtime.md`](../engine/remote-runtime.md)
