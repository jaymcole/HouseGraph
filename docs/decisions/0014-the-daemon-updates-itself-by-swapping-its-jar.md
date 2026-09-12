# 0014 — The daemon updates itself by swapping its jar and exiting

## Context

A server set up from the runbook tracked its *graphs* automatically and its own
build not at all: upgrading meant unloading the LaunchAgent, pulling the source,
waiting out a Gradle build and loading it again. Machines therefore sat on whatever
was built the day they were commissioned.

Two ways to fix that. **Build from source on the machine** — the exact procedure,
automated — or **install the jar the release workflow already builds**. Building
locally is what the runbook says to do, for a real reason: the shaded jar bundles
JavaFX's native libraries for the platform that built it. But it needs a source
clone, a toolchain and several minutes of CPU on a box whose job is running graphs,
and it can fail in ways an unattended machine cannot resolve.

Installing the release jar only became possible once the release workflow started
building on all three platforms and attaching `app-<version>-<platform>.jar`. That
is what made a downloaded jar safe to identify: the platform is in the name.

## Decision

Take the published jar. Match it on operating system **and** architecture, verify it
starts by running `--version` on it, swap it in by rename, and exit
`RESTART_REQUESTED` so the supervisor that keeps the daemon alive starts it again.

Off by default, because applying an update ends in the process exiting — correct
under `KeepAlive`, and indistinguishable from crashing without it.

## Consequences

An update costs a download and one restart rather than a build, and needs no source
clone on the server. Only machines the release matrix covers are updated: an Intel
Mac or an ARM Linux box is refused, by name, rather than handed a jar that would
fail at launch with a native-library error. Windows is refused outright, since a
running jar cannot be replaced there.

The daemon becomes a supervised process in the same sense its graphs are, which is
why it reuses their exit-code contract instead of inventing one.

What is *not* gained is verification: the jar is checked for origin, platform and
the version it claims, not against a signature. Enabling self-update is a decision
to run what that repository releases, the same shape of decision as naming a graphs
repository at all.

**Reference:** [`../engine/self-update.md`](../engine/self-update.md)
