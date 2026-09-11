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
| `housegraph plugins list [--json]` | Installed node libraries, or the full machine-readable catalog |
| `housegraph plugins install <url>` | Install one |
| `housegraph plugins update [id...]` | Update some or all |
| `housegraph nodes list [--json]` | Installed node types, or the full machine-readable catalog |
| `housegraph nodes check <graph.json>` | Whether a graph's nodes still match what's installed |
| `housegraph validate <graph.json> [--json]` | Dangling edges, type mismatches and data cycles, with a JSON Pointer per finding |
| `housegraph schema [graph\|catalog]` | The JSON Schema for the save format or the node catalog |
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

## Sending warnings to Discord

Tailing a file only helps when you are already at the keyboard. The log window can
forward records to a Discord channel instead, so a machine running unattended tells
you when something goes wrong.

1. In Discord, open the channel's **Settings ▸ Integrations ▸ Webhooks**, create a
   webhook, and **Copy Webhook URL**.
2. In HouseGraph, open **Tools ▸ Logs…** and click **External…**.
3. Tick **Send log records to a Discord webhook**, paste the URL, and pick the level
   to send at — **WARN** is the default and usually the right one. **ERROR** if you
   only want failures; anything lower will be noisy.
4. Click **Send test message** and check the channel, then **Save**.

What to expect:

- **Only what clears the level you picked is sent.** It is a separate level from the
  file's and the window's, so the file can stay at `DEBUG` while the channel gets
  warnings only.
- **Records are batched.** A burst arrives as one message a couple of seconds later
  rather than as fifty, which is also what keeps HouseGraph inside Discord's rate
  limit.
- **The graph never waits on Discord.** If the webhook is slow or unreachable, records
  are dropped rather than queued forever, and the next message that gets through says
  how many were lost. Nothing is lost from the log file.
- **The URL is stored as a secret**, encrypted, like a token — see
  [secrets.md](secrets.md). It appears in the secrets editor as
  `log.discord.webhook`; deleting it there switches the destination off.

Delivery failures are reported on the console, once when the webhook stops answering
and once when it comes back, so a dead webhook does not fill the log with itself.

The setting belongs to the machine HouseGraph is running on, so configure it on the
server, not on your laptop.

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
renamed, the data-directory layout changes, the restart/backoff behaviour changes, or
a log destination is added or changes what an operator has to configure.
