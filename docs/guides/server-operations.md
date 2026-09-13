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
| `housegraph update [--check]` | Update HouseGraph itself to the latest release, or just say what one is |
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
| `config/remote-state.json` | The last commit deployed, so a reboot is not treated as a change, and what self-update has already installed |
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

### One command

```bash
housegraph update
```

Downloads the release jar built for this platform, checks it starts, and puts it
where the running one is — keeping the old one as `housegraph.jar.previous`. Add
`--check` to see what it would do without doing it.

The daemon keeps running the build it started on until it is restarted:

```bash
launchctl kickstart -k gui/$(id -u)/com.jaymcole.housegraph
```

`kickstart -k` stops the running instance and starts it again — no unload/load pair,
and nothing to get half-done.

**On an Intel Mac, an ARM Linux box, or Windows this will refuse**, and say why.
Releases carry a jar for Apple Silicon macOS, x86-64 Linux and x86-64 Windows only,
and a running jar cannot be replaced at all on Windows. Build from source instead.

### Without any command at all

Set `selfUpdate.enabled` in `remote.json` and the daemon does the above on its own,
once an hour, restarting itself onto the new jar. See
[Part 10 of the setup guide](server-setup.md#10-optional-let-it-update-itself).

### By hand, from a release

What to do when you are on a build too old to have `housegraph update` at all — the
first upgrade on any server set up before v1.20.1. Stage it, prove it runs, and only
then put it in place, which is what the daemon's own updater does internally:

```bash
curl -fL -o /tmp/housegraph.jar \
  https://github.com/jaymcole/HouseGraph/releases/download/vX.Y.Z/app-X.Y.Z-macos.jar
```

```bash
java -jar /tmp/housegraph.jar --version
```

Only once that prints the version you expect:

```bash
launchctl bootout gui/$(id -u)/com.jaymcole.housegraph
mv /tmp/housegraph.jar ~/HouseGraph/housegraph.jar
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.jaymcole.housegraph.plist
housegraph --version
```

`curl -f` matters: without it, a wrong URL saves the error page **as your jar**. So does
staging in `/tmp` — writing straight over the jar a daemon is running truncates it under
the running process. And `bootout` before `mv`, not after: confirm the service is gone
(`launchctl print` says "Could not find service") before touching the file.

### Building it yourself

For a build that is not a release, or a machine the releases don't cover — an Intel Mac,
an ARM Linux box.

```bash
launchctl bootout gui/$(id -u)/com.jaymcole.housegraph
```

```bash
cd ~/HouseGraph-source && git pull && ./gradlew :app:shadowJar
```

```bash
cp app/build/libs/app-*.jar ~/HouseGraph/housegraph.jar
```

```bash
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.jaymcole.housegraph.plist
housegraph --version
```

Stop it first — copying over the jar while the daemon is running leaves it on a
half-copied file. Your graphs stop for as long as the build takes, so it is not
something to do casually, but nothing is lost: the daemon shuts them down through the
normal teardown path.

A locally built jar reports version `0.2.0`, the source tree's fallback. With
`selfUpdate` on, the daemon reads that as older than the latest release and replaces
your build with it — so turn self-update off while you are running one.

### Going back

Whichever way it was updated, the build it replaced is next to it:

```bash
launchctl bootout gui/$(id -u)/com.jaymcole.housegraph
mv ~/HouseGraph/housegraph.jar.previous ~/HouseGraph/housegraph.jar
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.jaymcole.housegraph.plist
```

Turn `selfUpdate` off in `remote.json` first, or the next check puts the newer
release straight back.

## Controlling the daemon

| Want to | Command |
| --- | --- |
| Start it | `launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.jaymcole.housegraph.plist` |
| Stop it | `launchctl bootout gui/$(id -u)/com.jaymcole.housegraph` |
| Restart it | `launchctl kickstart -k gui/$(id -u)/com.jaymcole.housegraph` |
| Is it running? | `launchctl print gui/$(id -u)/com.jaymcole.housegraph` |

`launchctl load`/`unload` still work but report almost every failure as
`Load failed: 5: Input/output error`, including a plist that is not where you think it
is. Use the four above.

**A plist edit needs `bootout` then `bootstrap`** — `kickstart` restarts the job as
launchd currently understands it and will not pick up a changed file.

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
renamed, the data-directory layout changes, the restart/backoff behaviour changes, the
way HouseGraph is updated on a server changes, or a log destination is added or changes
what an operator has to configure.
