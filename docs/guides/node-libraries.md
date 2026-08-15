# Node libraries

HouseGraph ships the engine, the editor, and a set of dependency-free primitive
nodes. Everything else — cameras, chat bots, web servers, machine learning — is a
**node library**: a jar fetched from a GitHub repository and loaded at runtime, with
no rebuild of the app.

## Before you install anything

**A node library is arbitrary code running with your full privileges.** It can read
your secrets store, your filesystem and the network. There is no sandbox.

Installing one is exactly as dangerous as running any program you downloaded.
Install libraries you trust the author of.

## Installing

Open **Node Libraries…** from the toolbar, and give it a GitHub repository URL.
HouseGraph looks up the latest release and shows you the repository, the asset, the
release and its size before fetching anything. Nothing is downloaded until you
confirm.

If a repository publishes several libraries in one release, you pick which to
install from a dropdown.

The first-party libraries are all in one repository:

```
https://github.com/jaymcole/housegraph-nodes
```

| Library | Provides |
| --- | --- |
| `housegraph-discord` | Discord Bot, Command, Slash Command, Reply, Send Message |
| `housegraph-reolink` | Discover Cameras, Camera Motion Status, Camera Snapshot |
| `housegraph-web` | Web Server, Node Server — LAN hosting on `<name>.local` |
| `housegraph-ml` | Animal Classifier — local JVM image classification, no Python |
| `housegraph-github` | Git Sync |
| `housegraph-squirrel` | Squirrel Alarm — an Arduino UNO R4 WiFi LED-matrix sign |
| `housegraph-filesystem` | Create Folder |
| `housegraph-experimental` | Lightbulb |

One release publishes all of them at the same version, so you pick which to
install.

From the command line:

```bash
housegraph plugins install https://github.com/jaymcole/housegraph-nodes
```

## Managing what you have

The library window's table supports multi-selection. **Update**, **Enable/Disable**
and **Remove** act on the whole selection, each still going through its own
confirmation, so one refusal does not abort the batch.

**Check for Updates** checks just the selection when rows are selected, or every
installed library when nothing is.

Updates are never checked automatically. GitHub's API allows 60 unauthenticated
requests per hour, so every lookup is a deliberate action.

## Changes may wait for a restart

Every change is written to disk immediately. But if any node from *any* library is
currently on your canvas, the in-memory reload is deferred and the change takes
effect on the next restart.

The library shows **Pending restart** in its Status column, with a live node count.
You will get one summary alert the first time this happens in a session; later
deferrals just update the status line.

This is not a limitation of the library you changed. Reloading rebuilds the class
loader for every enabled library at once, so a node still bound to the old class
would be stranded. Closing the graph — or restarting — resolves it.

## Opening a graph that needs a library you don't have

**The graph still opens.** Nodes from the missing library become placeholders: they
appear on the canvas, show as misconfigured, and refuse to run, but they keep all
their settings and connections. Saving again preserves them exactly.

You get a dialog offering **Open anyway** or **Install and open**. On a startup
reopen you get a toolbar notice instead, because reopening the app should not
depend on the network.

**The app never installs a library on its own.** A graph file you were sent can
propose a download, but only you can cause one. A server running graphs unattended
can install automatically, since there you named the repository they come from
yourself — see [server-setup.md](server-setup.md).

To check a graph before opening it:

```bash
housegraph check ~/graphs/porch-light.json
```

That reports which libraries the graph needs and whether you have them, exiting
non-zero when something is missing.

## Writing your own

Start from the
[plugin template](https://github.com/jaymcole/housegraph-plugin-template). See
[`../nodes/publishing-a-library.md`](../nodes/publishing-a-library.md).

---

**When you change this, update…** this file whenever the install flow, the library
window's actions, or the missing-library behaviour changes.
