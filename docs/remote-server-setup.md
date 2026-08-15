# Setting up a HouseGraph server

A step-by-step guide to running your graphs 24/7 on a dedicated machine — a Mac mini
in a cupboard — so they stop competing for your desktop. You keep your graphs in a
GitHub repository; when you push, the machine pulls and restarts them.

This is the practical runbook. For *why* it works the way it does — the trust model,
why sync resets instead of pulling, why the window is still there — see
[architecture/deployment.md](architecture/deployment.md).

**Roughly 30 minutes**, most of it waiting for a Gradle build.

> ### Want this automated?
>
> [`extras/setup-server.sh`](../extras/setup-server.sh) drives Parts 3–9 of this guide
> for you on a Mac — clones and builds the jar, sets up the deploy key, writes
> `remote.json`, installs node libraries, runs the `doctor`/`sync`/`daemon --once`
> checks, and installs the LaunchAgent. It pauses at the two steps only a human can
> do (pasting the deploy key into GitHub, flipping System Settings toggles) and is
> safe to re-run. Read on if you'd rather do it by hand, or to understand what the
> script is doing:
>
> ```bash
> git clone https://github.com/jaymcole/HouseGraph.git ~/HouseGraph-source
> ~/HouseGraph-source/extras/setup-server.sh --graphs-repo git@github.com:YOU/my-graphs.git
> ```

---

## Before you start

- A Mac you can log into and leave running. (Linux and Windows work too — only the
  auto-start step in [Part 8](#8-keep-it-running-across-reboots) is macOS-specific.)
- **JDK 21 or newer** on that machine: `java -version`. If it's missing, install a
  build from [Adoptium](https://adoptium.net/) or `brew install --cask temurin@21`.
- **git**: `git --version`. On macOS: `xcode-select --install`.
- A GitHub repository to keep your graphs in. It can be private.

> ### ⚠️ Build the jar on the Mac, not somewhere else
>
> The self-contained jar **bundles JavaFX's native libraries for the platform it was
> built on**. A jar built on Linux contains `.so` files and will not start on macOS;
> one built on an Intel Mac will not run natively on Apple Silicon. Copying a jar
> between platforms fails at launch with a JavaFX/native-library error that does not
> obviously say why.
>
> Build on the machine that will run it. [Part 3](#3-build-it-on-the-server) does this.

---

## 1. Make a repository for your graphs

This is separate from the HouseGraph source repository — it holds only your graphs.

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
  are absolute are refused — the server won't run a graph from outside the repo.
- `enabled: false` parks a graph in the repository without running it. Handy for
  seasonal or half-finished graphs.
- A repository with no `housegraph.json` runs nothing, and says so in the log.

Push it to GitHub. You don't need any graphs in it yet.

---

## 2. Author graphs that actually start ← read this one

**This is the step people get wrong.** The server opens your graph, but it does not
press Start for you. A trigger or resource node only comes back to life if it was
*running at the moment you saved the file*.

So, in the HouseGraph editor on your desktop:

1. Build your graph as usual.
2. **Press Start** (or Connect) on every trigger and resource node you want live —
   the Repeating Trigger, a Discord bot, a web server, and so on.
3. **Then save**, with those nodes still running.
4. Commit and push.

The running state rides along in the save file. You can check it — a live node has a
`"running": "true"` entry in its `state`:

```jsonc
{ "type": "TriggerRepeatingNode",
  "inputs": [ { "name": "Interval (s)", "value": 300 } ],
  "state": { "running": "true" } }        // ← without this, the graph does nothing
```

If you deploy a graph and nothing happens, this is almost always why. Open the file
and grep for `running`.

> Copy/paste deliberately does **not** carry this flag, so duplicating a running node
> in the editor never gives you a second one that auto-starts. Only save/load does.

---

## 3. Build it on the server

On the Mac mini. Keep the source clone and the jar you actually run in **separate
directories** — the paths below are used throughout this guide:

```bash
git clone https://github.com/jaymcole/HouseGraph.git ~/HouseGraph-source
cd ~/HouseGraph-source
./gradlew :app:shadowJar
```

Copy the jar somewhere stable, so your auto-start configuration doesn't break every
time you rebuild into `build/`:

```bash
mkdir -p ~/HouseGraph
cp app/build/libs/app-*.jar ~/HouseGraph/housegraph.jar
java -jar ~/HouseGraph/housegraph.jar --version
```

A shell alias makes the rest of this guide less repetitive:

```bash
echo "alias housegraph='java -jar ~/HouseGraph/housegraph.jar'" >> ~/.zshrc
source ~/.zshrc
housegraph --version
```

The rest of this guide writes `housegraph`; use `java -jar ~/HouseGraph/housegraph.jar`
if you skipped the alias.

---

## 4. Let the server read your graphs repository

### The easy way: an SSH deploy key

HouseGraph never touches a credential in this setup — git and your SSH agent handle
everything. On the Mac mini:

```bash
ssh-keygen -t ed25519 -C "housegraph-server" -f ~/.ssh/housegraph_deploy
cat ~/.ssh/housegraph_deploy.pub
```

Add that public key to your **graphs** repository on GitHub: *Settings → Deploy keys
→ Add deploy key*. Paste it, and **leave "Allow write access" unchecked** — the server
only ever reads.

Point SSH at the key by adding this to `~/.ssh/config`:

```
Host github.com
  IdentityFile ~/.ssh/housegraph_deploy
  IdentitiesOnly yes
```

Check it works before going further:

```bash
git ls-remote git@github.com:YOUR-NAME/my-graphs.git
```

That should print a commit id. If it asks for a password or says "Permission denied",
fix it here — nothing later will work until this does.

### The alternative: HTTPS with a token

If you'd rather use HTTPS, store a personal access token (scope: `repo`, read-only is
enough) in HouseGraph's encrypted secrets store via **Secrets…** in the editor, then
name its key in the config in the next step. The token is passed to git through its
environment, never on the command line where `ps` would expose it.

---

## 5. Configure the server

The config lives in HouseGraph's data directory. `housegraph doctor` prints the exact
path; on macOS it is:

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
| `pollSeconds` | how often to ask GitHub whether anything changed. 60 is a good default; the minimum is 5. This is a cheap `git ls-remote`, not an API call, so it isn't rate-limited. |
| `repositories[].url` | SSH or HTTPS. Track as many as you like. |
| `repositories[].branch` | which branch to follow — a `deploy` branch is a nice way to separate "written" from "live". |
| `repositories[].tokenSecret` | only for HTTPS: the name of the key in your secrets store holding the token. |
| `allowPluginInstall` | whether the server may install node libraries by itself. Off by default. See the next step. |

---

## 6. Node libraries

If your graphs use anything beyond the built-in nodes — cameras, Discord, the web
server — the server needs those libraries too. Otherwise those nodes load as
placeholders: the graph still opens and nothing is lost, but those nodes don't run.

**Recommended: install them yourself, once.**

```bash
housegraph plugins install https://github.com/jaymcole/housegraph-nodes
housegraph plugins list
```

Check a graph has everything it needs before deploying it:

```bash
housegraph check ~/some/porch-light.json
```

**Optional: let the repository install them.** Declare them in `housegraph.json`:

```jsonc
{
  "manifestVersion": 1,
  "graphs": [ { "file": "graphs/porch-light.json" } ],
  "plugins": [
    { "id": "housegraph-camera",
      "repository": "https://github.com/jaymcole/housegraph-nodes",
      "version": "0.4.0" }
  ]
}
```

…and open **both** gates in `remote.json`:

```jsonc
{
  "allowPluginInstall": true,
  "trustedPluginRepositories": ["https://github.com/jaymcole/housegraph-nodes"]
}
```

Both are required, and a repository not on the list is refused with a line in the log.
This is deliberate: **a node library is arbitrary code running with your full
privileges**, so nothing gets installed unattended unless you named the source by hand
in a file on your own machine.

### Keeping libraries up to date

`version` means **"at least this"**. When the installed library is behind it, the server
updates to the repository's latest release on the next poll and restarts the graphs from
that repository. So bumping the number in `housegraph.json` and pushing is how you roll a
new library out — you don't have to SSH in and run `plugins update`.

Leave `version` out and the library is installed once and never moved again, which is
what you want if you'd rather do updates by hand. A version string the comparison can't
read as numbers (`nightly`, say) is treated the same way: no update, rather than a guess.

`housegraph doctor` prints which gates are open and what's installed.

---

## 7. Test it before automating it

Work through these in order. Each one tells you something the next depends on.

```bash
# Is the machine ready? Checks git, the jar, config, and installed libraries.
housegraph doctor

# Pull the repositories now and report what would run. Starts nothing.
housegraph sync

# Sync, start everything, and exit. Your graphs should come alive here.
housegraph daemon --once
```

`sync` should list your graphs:

```
git@github.com:you/my-graphs.git: updated to 4a1c2f8, 2 graph(s)
    graphs/porch-light.json
    graphs/doorbell.json
```

Then run the real thing in the foreground and leave it going:

```bash
housegraph daemon
```

Push a change to your graphs repository from your desktop. Within `pollSeconds` the
log should show the new commit and the graphs restarting. Once you've seen that, stop
it with `Ctrl-C` — it shuts its graphs down cleanly on the way out — and automate it.

---

## 8. Keep it running across reboots

A **LaunchAgent**, not a LaunchDaemon: graphs run in the ordinary windowed app, so it
has to start inside your logged-in session. A LaunchDaemon runs before login, where
there's no window server, and would fail on every attempt.

```bash
cp ~/HouseGraph-source/extras/launchd/com.jaymcole.housegraph.plist ~/Library/LaunchAgents/
```

Edit the copy and set the two paths. For the java path, don't guess — ask:

```bash
/usr/libexec/java_home -v 21    # prints the JDK home; java is at <that>/bin/java
```

So `ProgramArguments` becomes something like:

```xml
<array>
    <string>/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java</string>
    <string>-jar</string>
    <string>/Users/you/HouseGraph/housegraph.jar</string>
    <string>daemon</string>
</array>
```

…and set both log paths to something real, e.g.
`/Users/you/Library/Logs/housegraph-daemon.log`.

Then load it:

```bash
launchctl load ~/Library/LaunchAgents/com.jaymcole.housegraph.plist
launchctl list | grep housegraph          # should show it running
```

On recent macOS the modern spelling is `launchctl bootstrap gui/$(id -u)
~/Library/LaunchAgents/com.jaymcole.housegraph.plist`; either works.

To stop it: `launchctl unload ~/Library/LaunchAgents/com.jaymcole.housegraph.plist`.

---

## 9. Make the Mac behave like a server

```bash
# Never sleep. A sleeping Mac runs no graphs.
sudo pmset -a sleep 0 disablesleep 1
```

And in **System Settings**:

- **Users & Groups → Automatic login** → your user. Without this, a reboot leaves the
  machine at the login screen with no GUI session, and nothing starts.
- **General → Sharing → Screen Sharing** on, so you can check on it without a monitor.
- **Energy → Start up automatically after a power failure** on.

---

## Day to day

Deploying a change is just:

```bash
git commit -am "Slow the porch light check to five minutes"
git push
```

Within `pollSeconds` the server pulls it and restarts the affected repository's
graphs. There is nothing to log into.

Restarting is deliberately the whole repository's graphs, not just the file you
edited — a commit can change several graphs at once, and a new node library only ever
takes effect in a fresh process.

### Where everything lives

Run `housegraph doctor` for the data directory; underneath it:

| Path | Holds |
| --- | --- |
| `config/remote.json` | your configuration |
| `config/remote-state.json` | the last commit deployed, so a reboot isn't treated as a change |
| `config/plugins.json` | installed node libraries |
| `config/plugin-trust.json` | the app's auto-install switch and trusted repositories (unused by the daemon, which reads `remote.json` instead) |
| `remotes/<owner>-<repo>/` | the local mirror of each graphs repository |
| `logs/housegraph.log` | everything the daemon and its graphs logged |
| `secrets/` | the encrypted secrets store |

**Never edit anything under `remotes/`.** It's a mirror; every sync resets it and your
changes are gone. Edit in your repository and push.

---

## When something's wrong

Start here:

```bash
tail -f ~/Library/Application\ Support/HouseGraph/logs/housegraph.log
```

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Graph loads but nothing ever happens | Its trigger wasn't running when you saved | [Part 2](#2-author-graphs-that-actually-start--read-this-one). Look for `"running": "true"` in the file. |
| `doctor` says git is MISSING | No command line tools | `xcode-select --install` |
| `doctor` says "not a jar" | Running from an IDE or exploded classes | Run the jar built in [Part 3](#3-build-it-on-the-server) |
| `sync` fails with a permission error | Deploy key not set up or not being offered | Re-check `git ls-remote` in [Part 4](#4-let-the-server-read-your-graphs-repository) |
| "no `housegraph.json` at its root" | Manifest missing or in a subfolder | It must be at the repository root |
| "Manifest lists X but there is no such file" | Path typo, or the graph wasn't committed | Check the path is relative to the repo root |
| Nodes show as placeholders | Missing node library | `housegraph check <graph>`, then `housegraph plugins install <url>` |
| "Not installing X … add it to trustedPluginRepositories" | The install gates are closed | Either install it by hand, or open both gates ([Part 6](#6-node-libraries)) |
| A graph restarts over and over | It fails on startup | The log has the reason. Retries slow to once a minute rather than spinning. |
| A graph stopped and won't retry | It exited with a configuration error | Deliberate — a permanent fault isn't retried forever. Fix it and push; a new commit revives it. |
| Nothing runs after a reboot | No auto-login, so no GUI session | [Part 9](#9-make-the-mac-behave-like-a-server) |
| Fails at launch complaining about native libraries | Jar built on a different platform | Rebuild on this machine ([Part 3](#3-build-it-on-the-server)) |

## Updating HouseGraph itself

```bash
launchctl unload ~/Library/LaunchAgents/com.jaymcole.housegraph.plist
cd ~/HouseGraph-source && git pull && ./gradlew :app:shadowJar
cp app/build/libs/app-*.jar ~/HouseGraph/housegraph.jar
launchctl load ~/Library/LaunchAgents/com.jaymcole.housegraph.plist
housegraph --version                      # confirm the new build is what's running
```

Unload first: the jar can't be replaced cleanly while it's running. Your graphs stop
for as long as this takes, so it isn't something to do casually — but nothing is lost,
because the daemon shuts them down through the normal teardown path.

---

## A note on what this does and doesn't isolate

Graphs run as child processes of the daemon, one per graph, so a graph that wedges or
crashes takes only itself down. But every graph runs **as you**, with your full
privileges and access to your secrets store. Node libraries are arbitrary code, and
there is no sandbox — see the threat model in
[architecture/plugins.md](architecture/plugins.md). Treat the graphs repository as
something only you can push to, and keep the deploy key read-only.

---

**When you change this, update…** this file whenever you change the shape of
`remote.json` or `housegraph.json`, add or rename a CLI command, change what
`doctor` checks, or change the prerequisites for a server. Keep it a *runbook* —
the reasoning behind these steps belongs in
[architecture/deployment.md](architecture/deployment.md), and the two should not
drift into restating each other.
