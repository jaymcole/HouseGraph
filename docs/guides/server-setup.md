# Setting up a HouseGraph server

Run your graphs 24/7 on a dedicated machine — a Mac mini in a cupboard — so they
stop competing for your desktop. You keep your graphs in a GitHub repository; when
you push, the machine pulls and restarts them.

**Roughly 30 minutes**, most of it waiting for a Gradle build.

Day-to-day operation is [server-operations.md](server-operations.md). For *why* it
works this way, see [`../engine/remote-runtime.md`](../engine/remote-runtime.md).

> ### Want this automated?
>
> [`scripts/setup-server.sh`](../../scripts/setup-server.sh) drives Parts 3–9 on a
> Mac: clones and builds the jar, sets up the deploy key, writes `remote.json`,
> installs node libraries, runs the checks, and installs the LaunchAgent. It pauses
> at the two steps only a human can do and is safe to re-run.
>
> ```bash
> git clone https://github.com/jaymcole/HouseGraph.git ~/HouseGraph-source
> ~/HouseGraph-source/scripts/setup-server.sh --graphs-repo git@github.com:YOU/my-graphs.git
> ```

---

## Before you start

- A Mac you can log into and leave running. Linux and Windows work too — only the
  auto-start step in [Part 8](#8-keep-it-running-across-reboots) is macOS-specific.
- **JDK 21 or newer**: `java -version`. If missing, install from
  [Adoptium](https://adoptium.net/) or `brew install --cask temurin@21`.
- **git**: `git --version`. On macOS: `xcode-select --install`.
- A GitHub repository for your graphs. It can be private.

> ### ⚠️ Build the jar on the Mac, not somewhere else
>
> The self-contained jar bundles JavaFX's native libraries for the platform it was
> built on. A jar built on Linux contains `.so` files and will not start on macOS;
> one built on an Intel Mac will not run natively on Apple Silicon. Copying a jar
> between platforms fails at launch with a native-library error that does not
> obviously say why.

---

## 1. Make a repository for your graphs

Separate from the HouseGraph source repository — it holds only your graphs.

```
my-graphs/
├── housegraph.json          ← tells the server what to run
└── graphs/
    ├── porch-light.json
    └── doorbell.json
```

`housegraph.json` at the **root**:

```jsonc
{
  "manifestVersion": 1,
  "graphs": [
    { "file": "graphs/porch-light.json" },
    { "file": "graphs/doorbell.json" },
    { "file": "graphs/summer-only.json", "enabled": false }
  ]
}
```

- `file` is relative to the repository root. Paths that climb outside it (`../`) or
  are absolute are refused.
- `enabled: false` parks a graph without running it. Handy for seasonal or
  half-finished graphs.
- A repository with no `housegraph.json` runs nothing, and says so in the log.

Push it. You do not need any graphs in it yet.

---

## 2. Author graphs that actually start

**This is the step people get wrong.** The server opens your graph, but it does not
press Start. A trigger or resource node only comes back to life if it was *running
at the moment you saved the file*.

In the HouseGraph editor on your desktop:

1. Build your graph as usual.
2. **Press Start** (or Connect) on every trigger and resource node you want live.
3. **Then save**, with those nodes still running.
4. Commit and push.

The running state rides along in the save file. A live node has a
`"running": "true"` entry in its `state`:

```jsonc
{ "type": "TriggerRepeatingNode",
  "inputs": [ { "name": "Interval (s)", "value": 300 } ],
  "state": { "running": "true" } }        // ← without this, the graph does nothing
```

If you deploy a graph and nothing happens, this is almost always why. Open the file
and search for `running`.

> Copy/paste deliberately does not carry this flag, so duplicating a running node
> in the editor never gives you a second one that auto-starts. Only save/load does.

**If a node binds a port or holds a connection**, leaving it running just to
survive a save has a sharper problem than forgetting a step: your desktop editor
and the deployed server are on the same LAN, so pressing Start locally to save the
graph starts a second copy fighting the server over the same port. For that case,
use an **`On Daemon Start`** trigger node (`control/`) instead of pressing Start
by hand. It has no running flag to forget — it fires only when the daemon's
supervisor opens the graph, never when you have it open to edit, so it is safe to
leave downstream nodes wired to it and just save normally.

---

## 3. Build it on the server

On the Mac mini. Keep the source clone and the jar you run in **separate
directories**; the paths below are used throughout.

```bash
git clone https://github.com/jaymcole/HouseGraph.git ~/HouseGraph-source
cd ~/HouseGraph-source && ./gradlew :app:shadowJar
```

Copy the jar somewhere stable, so your auto-start configuration does not break
every time you rebuild:

```bash
mkdir -p ~/HouseGraph && cp app/build/libs/app-*.jar ~/HouseGraph/housegraph.jar
```

```bash
java -jar ~/HouseGraph/housegraph.jar --version
```

An alias makes the rest of this guide less repetitive:

```bash
echo "alias housegraph='java -jar ~/HouseGraph/housegraph.jar'" >> ~/.zshrc && source ~/.zshrc
```

The rest of this guide writes `housegraph`; use the full command if you skipped it.

---

## 4. Let the server read your graphs repository

### Recommended: an SSH deploy key

HouseGraph never touches a credential in this setup — git and your SSH agent handle
everything.

```bash
ssh-keygen -t ed25519 -C "housegraph-server" -f ~/.ssh/housegraph_deploy
```

```bash
cat ~/.ssh/housegraph_deploy.pub
```

Add that public key to your **graphs** repository on GitHub: *Settings → Deploy
keys → Add deploy key*. Paste it and **leave "Allow write access" unchecked** — the
server only ever reads.

Point SSH at the key in `~/.ssh/config`:

```
Host github.com
  IdentityFile ~/.ssh/housegraph_deploy
  IdentitiesOnly yes
```

Check it before going further:

```bash
git ls-remote git@github.com:YOUR-NAME/my-graphs.git
```

That should print a commit id. If it asks for a password or says "Permission
denied", fix it here — nothing later will work until this does.

### Alternative: HTTPS with a token

Store a personal access token (scope `repo`, read-only is enough) in the secrets
store via **Secrets…**, then name its key in the next step. The token is passed to
git through its environment, never on the command line. See
[secrets.md](secrets.md).

---

## 5. Configure the server

`housegraph doctor` prints the exact path. On macOS:

```
~/Library/Application Support/HouseGraph/config/remote.json
```

```bash
mkdir -p ~/Library/Application\ Support/HouseGraph/config
```

Create `remote.json`:

```jsonc
{
  "pollSeconds": 60,
  "repositories": [
    { "url": "git@github.com:YOUR-NAME/my-graphs.git", "branch": "main" }
  ],
  "allowPluginInstall": false,
  "trustedPluginRepositories": []
}
```

| Key | Means |
| --- | --- |
| `pollSeconds` | How often to ask GitHub whether anything changed. 60 is a good default; the minimum is 5. This is a cheap `git ls-remote`, not an API call, so it is not rate-limited. |
| `repositories[].url` | SSH or HTTPS. Track as many as you like. |
| `repositories[].branch` | Which branch to follow. A `deploy` branch is a nice way to separate "written" from "live". |
| `repositories[].tokenSecret` | HTTPS only: the name of the secrets-store key holding the token. |
| `allowPluginInstall` | Whether the server may install node libraries by itself. Off by default. |
| `trustedPluginRepositories` | Optional. Empty means any GitHub repository your graphs name; list repositories to narrow it. |

---

## 6. Node libraries

If your graphs use anything beyond the built-in nodes, the server needs those
libraries too. Otherwise those nodes load as placeholders — the graph still opens
and nothing is lost, but they do not run.

**Recommended: let the server install them.**

```jsonc
{ "allowPluginInstall": true }
```

That is the whole configuration. On each sync the server reads the save files it is
about to run, sees which libraries they were built against and where they came
from, and installs what is missing before starting them.

One key is enough because **you already named the graph repository by hand in this
file**. The save files in it are your commits, in your repository. Fetching is
still limited to GitHub, and nothing is installed if `allowPluginInstall` is off.

**This applies only to the server.** The desktop app never installs on its own.

**Optional: narrow it.**

```jsonc
{
  "allowPluginInstall": true,
  "trustedPluginRepositories": ["https://github.com/jaymcole/housegraph-nodes"]
}
```

With that list non-empty, anything not on it is refused, with a log line saying so
and how to fix it.

**Optional: install by hand instead**, leaving `allowPluginInstall` off:

```bash
housegraph plugins install https://github.com/jaymcole/housegraph-nodes
```

```bash
housegraph check ~/some/porch-light.json
```

### Keeping libraries up to date

Automatic installs get a library *there*. A `plugins[]` entry in `housegraph.json`
moves one *forward*:

```jsonc
{
  "manifestVersion": 1,
  "graphs": [ { "file": "graphs/porch-light.json" } ],
  "plugins": [
    { "id": "housegraph-reolink",
      "repository": "https://github.com/jaymcole/housegraph-nodes",
      "version": "0.4.0" }
  ]
}
```

`version` means **"at least this"**. When the installed library is behind it, the
server updates to the repository's latest release on the next poll and restarts
that repository's graphs. Bumping the number and pushing is how you roll a library
out; you do not have to SSH in.

Leave the entry out and the library is installed once and never moved, which is
what you want if you would rather update by hand. A version string the comparison
cannot read as numbers — `nightly`, say — is treated the same way: no update,
rather than a guess.

```bash
housegraph doctor
```

prints which gates are open and what is installed.

> **Upgrading from an earlier build?** An empty `trustedPluginRepositories` used to
> mean "refuse everything"; it now means "don't narrow". If you have
> `allowPluginInstall: true` with an empty list, the server previously installed
> nothing and will now install what your graphs name. The startup log says so; add
> the list back for the old behaviour.

---

## 7. Test it before automating it

Work through these in order. Each tells you something the next depends on.

```bash
housegraph doctor
```

```bash
housegraph sync
```

```bash
housegraph daemon --once
```

`doctor` checks git, the jar, config and installed libraries. `sync` pulls and
reports what would run, starting nothing. `daemon --once` syncs, starts everything
and exits — your graphs should come alive here.

`sync` should list your graphs:

```
git@github.com:you/my-graphs.git: updated to 4a1c2f8, 2 graph(s)
    graphs/porch-light.json
    graphs/doorbell.json
```

Then run the real thing in the foreground:

```bash
housegraph daemon
```

Push a change from your desktop. Within `pollSeconds` the log should show the new
commit and the graphs restarting. Once you have seen that, stop it with `Ctrl-C` —
it shuts its graphs down cleanly — and automate it.

---

## 8. Keep it running across reboots

A **LaunchAgent**, not a LaunchDaemon: graphs run in the ordinary windowed app, so
it has to start inside your logged-in session. A LaunchDaemon runs before login,
where there is no window server, and would fail every time.

```bash
cp ~/HouseGraph-source/extras/launchd/com.jaymcole.housegraph.plist ~/Library/LaunchAgents/
```

Edit the copy and set the two paths. For the java path, ask rather than guess:

```bash
/usr/libexec/java_home -v 21
```

`java` is at `<that>/bin/java`. So `ProgramArguments` becomes something like:

```xml
<array>
    <string>/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java</string>
    <string>-jar</string>
    <string>/Users/you/HouseGraph/housegraph.jar</string>
    <string>daemon</string>
</array>
```

Set both log paths to something real, e.g.
`/Users/you/Library/Logs/housegraph-daemon.log`. Then load it:

```bash
launchctl load ~/Library/LaunchAgents/com.jaymcole.housegraph.plist
```

```bash
launchctl list | grep housegraph
```

On recent macOS the modern spelling is
`launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.jaymcole.housegraph.plist`;
either works. To stop it, `launchctl unload` the same path.

---

## 9. Make the Mac behave like a server

```bash
sudo pmset -a sleep 0 disablesleep 1
```

A sleeping Mac runs no graphs. And in **System Settings**:

- **Users & Groups → Automatic login** → your user. Without this, a reboot leaves
  the machine at the login screen with no GUI session, and nothing starts.
- **General → Sharing → Screen Sharing** on, so you can check on it without a
  monitor.
- **Energy → Start up automatically after a power failure** on.

---

## Next

- [server-operations.md](server-operations.md) — deploying changes, updating
  HouseGraph, where everything lives
- [troubleshooting.md](troubleshooting.md) — when something is wrong

---

**When you change this, update…** this file whenever the shape of `remote.json` or
`housegraph.json` changes, a CLI command is added or renamed, `doctor`'s checks
change, or the prerequisites change. Keep it a **runbook** — reasoning belongs in
[`../engine/remote-runtime.md`](../engine/remote-runtime.md), and the two should not
drift into restating each other.
