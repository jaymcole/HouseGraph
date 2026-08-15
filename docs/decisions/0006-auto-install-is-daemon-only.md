# 0006 — Auto-install exists only in the daemon

## Context

A save file records the node libraries it depends on, including the repository each
can be installed from. That makes "install what this graph needs" possible — and
raises the question of when it may happen without asking.

A save file is untrusted input. It may have arrived by email, been downloaded, or
been opened out of curiosity. It may **propose** a code download; it must never
**cause** one. And a node library is arbitrary code running with the user's full
privileges.

Two attempts at this were made before the current design.

**Trust-on-first-use in the desktop app.** A repository became trusted by ticking a
checkbox during an install. It was removed because it could not solve the case it
existed for: you had to have installed from a repository before it could ever
auto-install from one, so a fresh machine opening a graph still prompted — precisely
the situation worth automating. It optimised the second visit and left the first
untouched.

**Requiring every library to be restated in the daemon's manifest**, on the argument
that a save file's `plugins` table described what a graph was built against on
someone else's machine. That argument was withdrawn: these save files are commits in
a repository the operator named by hand, sitting beside the manifest they were being
contrasted with. Anyone who can add one can already edit the manifest. The
requirement was what stopped a fresh server working with no per-library
configuration.

## Decision

**The desktop app never auto-installs.** Every install is confirmed afresh, naming
repository, asset, release and size. There is deliberately no "remember this
repository".

**The daemon may**, gated by `allowPluginInstall` in the operator's own
`config/remote.json`, which defaults to false. Requirements come from both the
manifest and the save files being deployed, manifest first.

`trustedPluginRepositories` is an optional narrowing. **An empty list means "no
narrowing", not "nothing"** — a deliberate reversal of its earlier meaning.

## Consequences

The asymmetry is the design, not an unfinished corner. The difference is where the
graph came from: a save file the daemon runs is a commit in a repository the
operator hand-wrote into a file on their own machine. Naming that repository *is*
the trust decision, already made by a human before anything is fetched.

The human decision was not removed, only moved — from a dialog to the moment they
wrote the URL down.

`GitHubReleases.ALLOWED_HOSTS` still bounds every lookup and download to GitHub,
re-checked at download time, so the wider allowlist meaning is not a licence to
fetch from anywhere. `RemoteConfig.load` warns when installs are on with an empty
list, and `doctor` reports it, so it is never silent.

**Upgrade note:** a machine with `allowPluginInstall: true` and an empty list
previously installed nothing and now installs what its graphs name.

**Reference:** [`../engine/security-model.md`](../engine/security-model.md)
