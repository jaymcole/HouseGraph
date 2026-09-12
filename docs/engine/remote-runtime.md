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
        │  every selfUpdate.checkSeconds: is there a newer release?
        │  on a new one: swap the jar, exit 10, be restarted onto it
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
    { "id": "housegraph-reolink",
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
needing to know a supervisor exists. The daemon uses it for itself after installing
an update, which is the same request one layer up — the table is a contract between a
supervised process and its supervisor, and the daemon is one of those too. `20` stops
a permanent fault becoming a restart loop; `restartAll` clears it, because a new commit
may be exactly the fix.

Shutdown and the nested timeout chain are in
[node-lifecycle.md](node-lifecycle.md).

## Updating HouseGraph itself

The graphs repository is only half of what goes stale on an unattended machine; the
other half is the jar. `selfUpdate` in `remote.json` — **off by default** — has the
daemon install HouseGraph's latest GitHub release itself, and exit `10` so its
supervisor restarts it onto the new jar. The design, and every check standing between
a new release and a new jar, is in [self-update.md](self-update.md).

## Running a graph with no window

`housegraph run --headless <graph>` is the canvas-free runner. It stands up logging
and the node-library class loader, opens the graph through the same `GraphFileIO` →
`GraphLoader` path the canvas uses, resumes every `AutoStartable` node that was
running when the graph was saved, and stays up until the process is signalled. No
toolkit is started and no display is needed. `headless/HeadlessRunner` is the program;
`headless/HeadlessGraph` is the open-and-resume half of it.

The flag is opt-in and `housegraph run <graph>` still opens the editor, because
someone typing that at a desktop means the editor. It is deliberately not keyed off
`sdk.RuntimeMode.isDaemon()`: that says a supervisor started this JVM, which is a
different fact from "there is no display", and a person on a headless server has to
be able to ask for this directly.

Nothing in a loaded graph holds the JVM open by itself — the run executor is
virtual-thread-per-task and `NodeTimer`'s scheduler thread is a daemon — so the
runner waits on a latch its shutdown hook releases. `kill` is how the supervisor
restarts a graph, so that hook is the whole shutdown path: it runs the same three
steps as the windowed app (`NodeGraph.dispose()`, close the class loader, close the
log file), bounded, so a node that refuses to stop delays the restart rather than
blocking it. There is no `Platform.exit()` handoff to make, because there is no FX
thread teardown has to happen on.

What it refuses to start for is narrow: a graph file that is missing, unreadable or
unparseable is a `20`, and nothing else is. An uninstalled library is not, for the
reason above, and neither is a node that throws while being resumed — each resume is
isolated, and the failure is logged against the node and the library that owns it. A
partly live graph beats a dead one.

## Why the daemon still starts windowed children

`GraphProcess` builds `run <graph>`, not `run --headless <graph>`. The blocker is
out-of-tree: the presentation seam is additive on purpose, so a library that ignores
it compiles and behaves exactly as before — and is exactly as viewless-unsafe as
before. The Discord bot and web server nodes keep state in controls that
`createNodeContent()` builds, so resuming one headlessly throws. The runner survives
that node by node, but a graph whose point is its Discord bot is not usefully running
without it. What those libraries must change is in
[`../shared/node-library-rules.md`](../shared/node-library-rules.md).

Shipping the runner and changing what every deployed server does are also two
different risks, and they are worth taking one at a time. So the runner is driven by
hand until the first-party libraries in
[`housegraph-nodes`](https://github.com/jaymcole/housegraph-nodes) have adopted the
seam.

Two practical consequences of the windowed child, both called out in the runbook:

- **The jar bundles JavaFX's platform natives**, so it must be built on the machine
  that will run it, and the machine needs a logged-in GUI session.
- **The supervisor opens a graph; it never presses Start.** `AutoStartable` resumes
  a node only if it was running when the graph was saved. That is the correct
  semantics — liveness is user-driven — but it means a graph saved with its trigger
  stopped deploys successfully and then does nothing. A node that should run only
  under the supervisor, never on a desktop editing session, uses
  `sdk.RuntimeMode.isDaemon()` instead of a saved running flag — see
  [`../nodes/state-and-startup.md`](../nodes/state-and-startup.md). `GraphProcess`'s
  child launcher sets the `housegraph.daemon` system property on every process it
  spawns; that is the only place it is set. The headless runner sets nothing: it is
  the same program either way, and whether a supervisor started it is the
  supervisor's business.


## Commands

| Command | Does |
| --- | --- |
| `housegraph` | opens the editor on the last graph |
| `housegraph run <graph>` | opens the editor on one graph; what the supervisor starts |
| `housegraph run --headless <graph>` | runs one graph with no window, until the process is signalled |
| `housegraph daemon [--once]` | sync loop plus supervision |
| `housegraph sync [--force]` | pull now and report; starts nothing |
| `housegraph plugins list [--json] \| install <url> \| update [id...]` | node libraries from the terminal |
| `housegraph check <graph.json>` | dependency report; non-zero when something is missing |
| `housegraph update [--check]` | update HouseGraph itself from its latest GitHub release |
| `housegraph doctor` | is this machine ready? |

Global flags: `--home <dir>`, `--help`, `--version`.

The CLI shares the app's entry point. `Launcher` sends a bare first word to the
command table and everything else to `Application.launch`, with `run` the one bare
word that falls through, because opening a graph is the GUI. One jar, one
`Main-Class`, and `java -jar app.jar` with no arguments behaves exactly as it always
has. A bare word naming no command gets "Unknown command", not a window that
silently ignores what was typed.

`run` is where `Launcher` forks a second time, on `--headless`. Neither form is in
the command table, because neither behaves like a command: one opens a window and
one stays up until it is signalled, where every entry in the table returns a code and
exits. Both find the graph by the same rule — the first bare argument, or an explicit
`--graph=`.

---

**When you change this, update…** this file whenever you change the sync strategy,
the manifest or config format, supervision or backoff behaviour, the exit-code
contract, the CLI surface, the headless runner, how the daemon updates itself, or what
still stands between the daemon and headless children. Config-shape changes also touch
[`../guides/server-setup.md`](../guides/server-setup.md); trust changes belong in
[security-model.md](security-model.md).
