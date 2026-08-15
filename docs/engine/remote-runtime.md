# Remote runtime

The desktop app is one person, one window, one graph. The remote runtime is the
other shape: a machine that runs graphs continuously, takes them from a git
repository, and restarts them when you push.

The operator runbook is [`../guides/server-setup.md`](../guides/server-setup.md).
This document is the design.

```
launchd (LaunchAgent, KeepAlive)
  └── housegraph daemon                     ← headless, one process
        │  every pollSeconds: git ls-remote
        │  on change: fetch + reset --hard + clean
        │  install declared libraries (if permitted)
        └── one child JVM per graph
              java -jar app.jar run <graph>  ← the ordinary JavaFX app
```

Two files drive it, with deliberately different owners:

| File | Lives | Written by | Says |
| --- | --- | --- | --- |
| `config/remote.json` | this machine, under `AppDirectories` | the operator, by hand | which repositories to track, and what may be installed |
| `housegraph.json` | the root of each tracked repository | whoever maintains the graphs | which graphs to run, and which libraries they need |

Everything the daemon fetches traces back to a URL written by hand in
`remote.json`. A graph repository can *ask* for a node library; it cannot widen the
set of places one may come from. The trust reasoning is in
[security-model.md](security-model.md).

## The manifest

```jsonc
{
  "manifestVersion": 1,
  "graphs": [
    { "file": "graphs/porch.json" },
    { "file": "graphs/winter-only.json", "enabled": false }
  ],
  "plugins": [
    { "id": "housegraph-camera",
      "repository": "https://github.com/jaymcole/housegraph-nodes",
      "version": "0.4.0" }
  ]
}
```

A repository with no manifest runs nothing, and says so. `enabled: false` parks a
graph without running it.

**`plugins[]` is optional.** A library a save file names with a repository URL is
installed without being listed. List one to set a version floor, or when a graph
names a library bare, as a v1 save does.

**`plugins[].version` means "at least this".** When the installed library is
behind it, the daemon updates to the repository's *latest* release — latest rather
than that exact version, because `GitHubReleases` has no fetch-by-tag and the
newest release satisfies "at least". An entry with no `version` installs once and is
never moved again.

The comparison is `GraphDependencyCheck.isOlder`, deliberately lenient: a version
scheme it cannot parse produces no update rather than a wrong one, since a false
positive downloads a jar and restarts a graph for nothing.

## Where requirements come from

`RemoteDeployment.requirementsOf` gathers in precedence order:

1. the manifest's `plugins[]`, then
2. `GraphDependencyCheck.requiredBy` over every save file the manifest deploys.

`GraphDependencyCheck.classify` keeps the **first** entry per id, so the manifest
wins on conflict: its `version` is a floor someone wrote down, while a save file's
is whatever the authoring machine happened to have. `AutoInstallPlan.from` splits
that into `INSTALL`/`UPDATE` actions and refusals, and `PluginInstaller.apply`
carries them out.

Reading both sources is what lets a fresh server come up with no per-library
configuration. The manifest still earns its keep for the version floor, for
deciding which graphs run, and for supplying a repository for a library a save file
names bare.

## The sync

A mirror, never a working copy:

| Step | Command |
| --- | --- |
| poll | `git ls-remote <url> refs/heads/<branch>` |
| first sync | `git clone --depth 1 --branch <branch>` |
| update | `git fetch --depth 1` → `git reset --hard FETCH_HEAD` → `git clean -fd` |

**Never `pull`.** A pull can conflict, and a conflict on an unattended machine is a
silent hang with nobody to resolve it — the daemon would sit there believing it was
up to date. Reset cannot fail that way. The cost is that anything edited by hand
inside the clone is discarded, which is correct for a directory whose purpose is to
reflect what was pushed. The `clean -fd` matters too: a reset alone leaves untracked
files behind, so a graph deleted upstream would keep running.

The remote is asked first and the mirror touched only when the answer differs, so
the steady state costs one `ls-remote` and no disk writes.

`pollSeconds` has a floor of 5. `git ls-remote` is a single short-lived connection
speaking the git protocol, not `api.github.com`, so the 60-requests-per-hour budget
that forces `GitHubReleases` to check only on user action does not apply. That
asymmetry is why a once-a-minute poll is reasonable here.

`config/remote-state.json` records the last commit deployed per repository, so a
rebooted machine does not treat everything as changed and bounce every graph.

## Supervision

One child JVM per graph. A graph whose node wedges takes only itself down, and
restarting it does not interrupt the others.

**On a repository change, every graph from it is restarted.** Blunter than
reloading in place, and deliberately so. `NodeGraph.dispose()` shuts its executors
down permanently, so an in-place reload needs a new engine anyway, and it still
could not pick up a node-library update, because `App.tryReloadNodeLibraries`
refuses to rebuild the class loader while library nodes are live. A new process
picks up new graphs *and* new libraries by one mechanism, with no second code path
that only works sometimes.

**Backoff is not optional.** A graph that fails immediately — a missing secret, a
port already bound — would otherwise restart as fast as a JVM can start, pinning a
core and burying the real error under thousands of identical log lines. The delay
doubles from 1s to a 60s cap and resets once a run has lasted a minute, so an
occasional crash recovers promptly while a permanent fault settles into a slow,
readable retry.

A graph needing a library that could not be installed still runs. Its nodes load as
`MissingNode` placeholders and the log says what was skipped and why. Refusing to
start would turn one unavailable library into a dead machine.

### Exit codes

`remote/ExitCodes` is the contract between a supervised app and its supervisor. An
exit code is the one channel that works no matter what state the child is in — no
socket to keep open, nothing to go stale if the JVM dies mid-sentence.

| Code | Meaning | Supervisor does |
| --- | --- | --- |
| `0` | finished | restart — it is supposed to stay up |
| `10` | restart me | restart at once, backoff reset |
| `20` | configuration error | log it and stop, until the repository changes |

`10` is the seam a future automation node uses to ask for a fresh JVM without
needing to know a supervisor exists. `20` stops a permanent fault becoming a restart
loop; `restartAll` clears it, because a new commit may be exactly the fix.

Shutdown and the nested timeout chain are in
[node-lifecycle.md](node-lifecycle.md).

## Why graphs still run in a window

The engine is headless. **Graph execution is not**, in two ways:

1. There is no canvas-free path from a save file to a live `NodeGraph`; `place()`
   lives in `GraphCanvas`.
2. Several nodes keep runtime state in JavaFX controls. `TriggerRepeatingNode` uses
   a `javafx.animation.Timeline` as its clock and writes a status label and start
   button from `start()`, all null unless `createNodeContent()` ran.
   `EchoResourceNode` is the same, as are the Discord bot and web server nodes out
   of tree. `autoStartIfWasRunning()` would throw.

So the child is the real app, window and all, exactly as it would be run by hand.
Node views are what run `createNodeContent()`, so a window that was never shown
would be a graph with half-initialised nodes.

A true headless runner needs both gaps closed: a canvas-free loader, and a
lifecycle seam that keeps a node's running state in the node. The second is a
cross-repo change touching every out-of-tree library. The CLI's command surface is
designed so that backend can slot in behind `run` without changing how the daemon
is operated.

Two practical consequences, both called out in the runbook:

- **The jar bundles JavaFX's platform natives**, so it must be built on the machine
  that will run it.
- **The supervisor opens a graph; it never presses Start.** `AutoStartable` resumes
  a node only if it was running when the graph was saved. That is the correct
  semantics — liveness is user-driven — but it means a graph saved with its trigger
  stopped deploys successfully and then does nothing.

## Commands

| Command | Does |
| --- | --- |
| `housegraph` | opens the editor on the last graph |
| `housegraph run <graph>` | opens the editor on one graph; what the supervisor starts |
| `housegraph daemon [--once]` | sync loop plus supervision |
| `housegraph sync [--force]` | pull now and report; starts nothing |
| `housegraph plugins list \| install <url> \| update [id...]` | node libraries from the terminal |
| `housegraph check <graph.json>` | dependency report; non-zero when something is missing |
| `housegraph doctor` | is this machine ready? |

Global flags: `--home <dir>`, `--help`, `--version`.

The CLI shares the app's entry point. `Launcher` sends a bare first word to the
command table and everything else to `Application.launch`, with `run` the one bare
word that falls through, because opening a graph is the GUI. One jar, one
`Main-Class`, and `java -jar app.jar` with no arguments behaves exactly as it always
has. A bare word naming no command gets "Unknown command", not a window that
silently ignores what was typed.

---

**When you change this, update…** this file whenever you change the sync strategy,
the manifest or config format, supervision or backoff behaviour, the exit-code
contract, or the CLI surface. Config-shape changes also touch
[`../guides/server-setup.md`](../guides/server-setup.md); trust changes belong in
[security-model.md](security-model.md).
