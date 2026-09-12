# Updating HouseGraph itself

The daemon keeps a machine's *graphs* current by pulling a git repository. This
document is the other half: keeping the **jar** current, from the releases GitHub
already builds.

The loop it runs inside is [remote-runtime.md](remote-runtime.md). The operator
runbook is [`../guides/server-operations.md`](../guides/server-operations.md).

```jsonc
// config/remote.json
{ "selfUpdate": { "enabled": true, "checkSeconds": 3600 } }
```

**Off by default**, and for a sharper reason than the node-library install gate:
applying an update ends with this process exiting, so it is only correct where
something restarts the daemon. Under the LaunchAgent's `KeepAlive` — or systemd's
`Restart=always` — that is a restart. In a terminal it is the daemon appearing to
stop by itself.

`repository` is the third key, defaulting to HouseGraph upstream. It is a knob for a
fork, and it is the trust decision here in exactly the way the graph repositories are:
a URL written by hand, in a file on this machine, naming whose code this machine will
run. See [security-model.md](security-model.md).

## Swap the jar, then exit

A JVM cannot become a different build of itself, so an update is two halves: replace
the jar on disk, and let whatever started this process start it again. The daemon
returns `ExitCodes.RESTART_REQUESTED` — the same `10` a supervised graph uses to ask
for a fresh JVM, one layer up.

Nothing else has to know an update happened. The shutdown hook stops the graphs the
way it always does, and they come back under the new jar because `GraphProcess`
launches children from whatever is at that path now.

Replacing a file underneath a running JVM is safe on Unix precisely because the swap
is a rename: the daemon and its children keep the inode they already opened and carry
on with the old jar until each is next started. It is not safe on Windows, which will
not let an open jar be replaced, so `SelfUpdater.canApply` refuses there **before**
anything is downloaded rather than after.

## What stands between a new release and a new jar

Each of these exists because the failure it prevents is unattended, and therefore
silent.

| Check | Refuses when | Why |
| --- | --- | --- |
| `AppVersion.current()` | there is no version in the manifest | a development build is not behind a release, and there is nothing honest to compare |
| `GraphDependencyCheck.isOlder` | the release is not newer | the same leniency the plugin updater uses: a version scheme it cannot parse says nothing rather than guessing |
| `UpdatePlatform` | no release jar matches this OS *and* architecture | the shaded jar carries JavaFX's natives for the machine that built it |
| verify | `java -jar <staged> --version` fails, or reports another version | catches a truncated download, a jar needing a newer Java, and an asset that is not what the release advertised |
| `appliedVersion` in `remote-state.json` | this machine already installed this release | if a swap does not take, the version comparison stays true forever — remembering it turns an hourly loop into one log line |

The release workflow publishes `app-<version>-<platform>.jar` for Linux, macOS and
Windows, and `UpdatePlatform` matches that suffix and nothing looser. Architecture is
part of the match rather than a detail: the macOS leg runs on Apple Silicon and the
other two on x86-64, so an **Intel Mac or an ARM Linux box is a machine no published
jar fits**. It is told so, rather than handed the jar that merely shares an operating
system, because a jar with the wrong natives fails at launch with an error that does
not say why. Releases cut before that workflow attached a single jar with no platform
in its name, and those are left alone for the same reason.

The jar that was replaced stays beside the new one as `<jar>.previous`. On a machine
with nobody at the keyboard that is the whole rollback story, and it is why the old
jar is copied aside *before* the new one is moved over it: at no point is there no
runnable jar at that path.

## The check is cheap, the budget is not

This is an `api.github.com` request, against the 60-per-hour-per-IP budget that shapes
`GitHubReleases` — not a `git ls-remote`. So `checkSeconds` defaults to an hour and has
a floor of 300, and `remote-state.json` keeps the lookup's `ETag`: a conditional
request answered 304 does not count against the budget, which matters most on a machine
whose supervisor is restarting it for some other reason.

The ETag is recorded only when the answer needs no further action. An update that was
found but not installed is deliberately **not** remembered — a download that failed has
to be retried, not filed as handled. After a successful install the ETag is cleared, so
the next check gets a full answer and can notice, and say, that the swap did not take.

## The two ways to ask

`housegraph update [--check]` is the same code driven by a person, and it is not gated
on `enabled`: that setting says whether the *daemon* updates itself, and someone typing
the command has already decided. `daemon --once` never updates, because exiting to be
restarted is not what a smoke test should do.

---

**When you change this, update…** this file whenever the `selfUpdate` config shape
changes, a check between a release and an installed jar is added or dropped, the
release workflow changes how it names or builds platform jars, or the restart
mechanism changes. Config-shape changes also touch
[`../guides/server-setup.md`](../guides/server-setup.md), and what an operator does
with them [`../guides/server-operations.md`](../guides/server-operations.md).
