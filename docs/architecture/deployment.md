# Deployment — running HouseGraph unattended

> **Status: host side complete.** The CLI, the git sync loop and the process
> supervisor are in `app`'s `cli/` and `remote/` packages, all headless and tested.
> Graph *execution* still runs in the ordinary JavaFX app, supervised as a child
> process — see [Why the window is still there](#why-the-window-is-still-there).

The desktop app is one person, one window, one graph. This document is about the
other shape: a machine in a cupboard that runs your graphs continuously, takes its
graphs from a git repository, and restarts them when you push.

## The picture

```
launchd (LaunchAgent, KeepAlive)
  └── housegraph daemon                     ← headless, one process
        │  every pollSeconds: git ls-remote
        │  on change: fetch + reset --hard + clean
        │  install declared libraries (if permitted)
        └── one child JVM per graph
              java -jar app.jar run <graph>
                                            ← the ordinary JavaFX app
```

Two files drive it, and they are deliberately different files with different
owners:

| File | Lives | Written by | Says |
| --- | --- | --- | --- |
| `config/remote.json` | this machine, under `AppDirectories` | the operator, by hand | which repositories to track, and what may be installed |
| `housegraph.json` | the root of each tracked repository | whoever maintains the graphs | which graphs to run, and which libraries they need |

## `config/remote.json` — the trust boundary

```jsonc
{
  "pollSeconds": 60,
  "repositories": [
    { "url": "git@github.com:jaymcole/my-graphs.git", "branch": "main" }
  ],
  "allowPluginInstall": false,
  "trustedPluginRepositories": []
}
```

Everything the daemon will ever fetch traces back to a URL written here by hand.
That is the whole point. `plugins.md` states the rule plainly — **a save file is
untrusted input proposing a code download, and must never be acted on silently** —
and a repository fetched over the network is no different. A graph repository can
*ask* for a node library; it cannot widen the set of places one may come from.

Auto-install is gated twice:

- `allowPluginInstall` is **false** unless the operator turns it on, and
- even then, a library is installed only if its repository appears in
  `trustedPluginRepositories`.

A graph needing something outside that set still runs. Its nodes load as
`MissingNode` placeholders — already safe, already lossless — and the log says
exactly what was skipped and why. Refusing to start would be worse: it turns one
unavailable library into a dead machine.

`pollSeconds` has a floor of 5. `git ls-remote` is a single short-lived connection
speaking the **git protocol**, not `api.github.com`, so the 60-requests-per-hour
budget that forces `GitHubReleases` to check only on user action does not apply
here. That asymmetry is why a once-a-minute poll is reasonable where an API poll
would not be.

### Credentials

**Use an SSH deploy key.** With an SSH URL, HouseGraph never sees a credential at
all — the key is the user's, handled by their agent, and there is nothing for this
project to store or leak.

For HTTPS against a private repository, name a `SecretsStore` key:

```jsonc
{ "url": "https://github.com/jaymcole/my-graphs.git", "tokenSecret": "GITHUB_TOKEN" }
```

The token is passed to git through `GIT_ASKPASS` and the child's **environment**,
never in the remote URL, because `argv` is readable by every process on the machine
(`ps`) while a child's environment is not. `GIT_TERMINAL_PROMPT=0` goes with it, so
a wrong token fails instead of blocking forever on a prompt nobody will answer.

## `housegraph.json` — what a repository asks for

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
graph in the repository without running it.

**`graphs[].file` is checked for containment.** It is a path from a file fetched
over the network, so it is resolved and then verified to still be inside the clone;
`../` and absolute paths are refused rather than clamped. Without that check, a
manifest could have the daemon load and execute a graph from anywhere on the disk —
and a graph's nodes run with the user's full privileges.

### Why a manifest, when save files now record their dependencies

`GraphFileIO` writes a `plugins` table carrying each library's repository URL, so
the daemon *could* read its dependencies straight out of the graphs. It
deliberately does not. That table describes what a graph was built against on
someone else's machine; the manifest is an explicit statement of intent, reviewed
in a commit, in a repository the operator named by hand. The save-file table still
earns its keep — it drives the interactive "install and open" offer in the app,
where a person is present to confirm.

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
inside the clone is discarded, which is the correct trade for a directory whose
entire purpose is to reflect what was pushed. The `clean -fd` matters too: a reset
alone leaves untracked files behind, so a graph deleted upstream would keep running.

The remote is asked first and the mirror only touched when the answer differs, so
the steady state — nothing pushed — costs one `ls-remote` and no disk writes.

`config/remote-state.json` records the last commit deployed per repository, so a
rebooted machine doesn't treat everything as changed and bounce every graph at
exactly the moment you want things to come up quietly.

## Supervision

One child JVM per graph. Isolation is the point: a graph whose node wedges takes
only itself down, and restarting it doesn't interrupt the others.

**On a repository change, every graph from it is restarted.** Blunter than
reloading a graph in place, and deliberately so — `NodeGraph.dispose()` shuts its
executors down permanently, so an in-place reload needs a new engine anyway, and it
still could not pick up a node-library update, because `App.tryReloadNodeLibraries`
refuses to rebuild the class loader while library nodes are live. A new process
picks up new graphs *and* new libraries by one mechanism, with no second code path
that only works sometimes.

**Backoff is not optional.** A graph that fails immediately — a missing secret, a
port already bound — would otherwise be restarted as fast as a JVM can start,
pinning a core and burying the real error under thousands of identical log lines.
The delay doubles from 1s to a 60s cap and resets once a run has lasted a minute,
so an occasional crash still recovers promptly while a permanent fault settles into
a slow, readable retry.

### Exit codes

`remote/ExitCodes` is the contract between a supervised app and its supervisor. An
exit code is the one channel that works no matter what state the child is in — no
socket to keep open, nothing to go stale if the JVM dies mid-sentence.

| Code | Meaning | Supervisor does |
| --- | --- | --- |
| `0` | finished | restart (it is supposed to stay up) |
| `10` | restart me | restart at once, backoff reset |
| `20` | configuration error | log it and stop, until the repository changes |

`10` is the seam a future automation node uses to ask for a fresh JVM without
needing to know a supervisor exists. `20` is what stops a permanent fault becoming
a restart loop; `restartAll` clears it, because a new commit may be exactly the fix.

## Stopping cleanly, and the bug this fixed

JavaFX calls `Application.stop()` when the platform exits, but **not** when the JVM
is signalled. Before this work there was no shutdown hook anywhere in the codebase,
so a `kill` — which is exactly how a supervisor restarts a graph — skipped
`App.stop()` entirely: `NodeGraph.dispose()` never ran, so no node's `onRemoved()`
ran either, and connections, child processes and timers were all left to the OS,
with the tail of the log never reaching disk.

`App` now installs a hook that calls `Platform.exit()` and waits on a latch counted
down at the end of `stop()`, with a 15-second timeout so one wedged node delays a
restart rather than blocking it forever. `GraphProcess.stop` is the other half:
`destroy()` (SIGTERM), wait 20s, `destroyForcibly()` only if it won't go.

This was a leak on every ordinary `kill` and logout, not only under supervision.

## Why the window is still there

The engine is headless — `NodeGraph` has no JavaFX imports and dispatches its
callbacks through an injectable executor. But **graph execution is not**, in two
ways that matter:

1. There is no canvas-free path from a save file to a live `NodeGraph`; `place()`
   lives in `GraphCanvas`.
2. Several nodes keep their *runtime state in JavaFX controls*.
   `TriggerRepeatingNode` uses a `javafx.animation.Timeline` as its clock and writes
   `statusLabel`/`startButton` from `start()` — all null unless
   `createNodeContent()` ran. `EchoResourceNode` is the same, as are the Discord bot
   and web server nodes out of tree. `autoStartIfWasRunning()` would throw.

So the child is the real app, window and all, exactly as it would be run by hand.
Node views are what run `createNodeContent()`, so a window that was never shown
would be a graph with half-initialised nodes.

**A true headless runner needs both gaps closed** — a canvas-free loader, and a
lifecycle seam that keeps a node's running state in the node — and the second is a
cross-repo change touching every out-of-tree library. The CLI's command surface is
designed so that backend can slot in behind `run` without changing how the daemon
is operated.

The practical consequence: the Mac mini needs a logged-in GUI session. Enable
automatic login, and keep it awake:

```bash
sudo pmset -a sleep 0 disablesleep 1
```

## Setting one up

The step-by-step runbook is a separate document, because it's a task rather than a
design: **[remote-server-setup.md](../remote-server-setup.md)**. In outline —
build the jar *on the server*, write `config/remote.json`, work up through
`doctor` → `sync` → `daemon --once` → `daemon`, then install
`extras/launchd/com.jaymcole.housegraph.plist` as a LaunchAgent.

Two consequences of the design above bite hardest in practice, and both are called
out there:

- **The jar bundles JavaFX's platform natives**, so it must be built on the machine
  that will run it. Nothing in this design could fix that short of dropping the
  window, which is the headless work below.
- **`AutoStartable` resumes a node only if it was running when the graph was saved.**
  The supervisor opens a graph; it never presses Start. That is the correct
  semantics — liveness is user-driven, per [resources.md](resources.md) — but it
  means a graph saved with its trigger stopped deploys successfully and then does
  nothing at all.

## Commands

| Command | Does |
| --- | --- |
| `housegraph` | opens the editor on the last graph, exactly as before |
| `housegraph run <graph>` | opens the editor on one graph; what the supervisor starts |
| `housegraph daemon [--once]` | sync loop plus supervision |
| `housegraph sync [--force]` | pull now and report; starts nothing |
| `housegraph plugins list \| install <url> \| update [id...]` | node libraries from the terminal |
| `housegraph check <graph.json>` | dependency report; non-zero when something is missing |
| `housegraph doctor` | is this machine ready? |

Global: `--home <dir>`, `--help`, `--version`.

The CLI shares the app's entry point: `Launcher` sends a bare first word to the
command table and everything else to `Application.launch`, with `run` the one bare
word that falls through — because opening a graph *is* the GUI. One jar, one
`Main-Class`, and `java -jar app.jar` with no arguments behaves exactly as it always
has. A bare word that names no command gets "Unknown command", not a window that
silently ignores what you typed.

---

**When you change this, update…** this file whenever you change the sync strategy,
the manifest or config format, the supervision or backoff behaviour, the exit-code
contract, or the trust model for unattended installs. A change to the save file's
`plugins` table also touches [ui.md](ui.md) and [plugins.md](plugins.md); a change
to shutdown behaviour also touches `App`'s Javadoc.
