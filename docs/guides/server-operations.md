# Running a HouseGraph server

Day-to-day operation of a machine set up with [server-setup.md](server-setup.md).

## Deploying a change

```bash
git commit -am "Slow the porch light check to five minutes" && git push
```

Within `pollSeconds` the server pulls it and restarts the affected repository's
graphs. There is nothing to log into.

Restarting is the whole repository's graphs, not just the file you edited: a commit
can change several graphs at once, and a new node library only ever takes effect in
a fresh process.

## Commands

| Command | Does |
| --- | --- |
| `housegraph doctor` | Is this machine ready? Checks git, the jar, config and libraries |
| `housegraph sync [--force]` | Pull now and report; starts nothing |
| `housegraph daemon [--once]` | Sync loop plus supervision |
| `housegraph check <graph.json>` | Which libraries a graph needs, and whether you have them |
| `housegraph plugins list` | Installed node libraries |
| `housegraph plugins install <url>` | Install one |
| `housegraph plugins update [id...]` | Update some or all |
| `housegraph run <graph>` | Open the editor on one graph |

Global flags: `--home <dir>`, `--help`, `--version`.

## Where everything lives

Run `housegraph doctor` for the data directory. Underneath it:

| Path | Holds |
| --- | --- |
| `config/remote.json` | Your configuration |
| `config/remote-state.json` | The last commit deployed, so a reboot is not treated as a change |
| `config/plugins.json` | Installed node libraries |
| `plugins/` | The downloaded library jars |
| `remotes/<owner>-<repo>/` | The local mirror of each graphs repository |
| `logs/housegraph.log` | Everything the daemon and its graphs logged |
| `secrets/` | The encrypted secrets store |

**Never edit anything under `remotes/`.** It is a mirror; every sync resets it with
`reset --hard` and `clean -fd`, and your changes are gone. Edit in your repository
and push.

## Reading the log

```bash
tail -f ~/Library/Application\ Support/HouseGraph/logs/housegraph.log
```

The file rotates at 5 MiB, keeping 5 generations, so it never grows without bound.
Graph output and daemon output both land here.

## Updating HouseGraph itself

```bash
launchctl unload ~/Library/LaunchAgents/com.jaymcole.housegraph.plist
```

```bash
cd ~/HouseGraph-source && git pull && ./gradlew :app:shadowJar
```

```bash
cp app/build/libs/app-*.jar ~/HouseGraph/housegraph.jar
```

```bash
launchctl load ~/Library/LaunchAgents/com.jaymcole.housegraph.plist
```

```bash
housegraph --version
```

Unload first — the jar cannot be replaced cleanly while it is running. Your graphs
stop for as long as this takes, so it is not something to do casually, but nothing
is lost: the daemon shuts them down through the normal teardown path.

## Restarts and backoff

A graph that crashes is restarted. The delay doubles from 1 second to a 60-second
cap and resets once a run has lasted a minute, so an occasional crash recovers
promptly while a permanent fault settles into a slow, readable retry rather than
spinning.

A graph that exits with a **configuration error** is not retried. That is
deliberate — a permanent fault should not loop forever. Fix it and push; a new
commit revives it.

## What this does and does not isolate

Graphs run as child processes of the daemon, one per graph, so a graph that wedges
or crashes takes only itself down.

But **every graph runs as you**, with your full privileges and access to your
secrets store. Node libraries are arbitrary code and there is no sandbox. Treat the
graphs repository as something only you can push to, and keep the deploy key
read-only.

---

**When you change this, update…** this file whenever a CLI command is added or
renamed, the data-directory layout changes, or the restart/backoff behaviour
changes.
