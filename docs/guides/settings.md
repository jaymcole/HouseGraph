# Settings

**Tools ▸ Settings…** (`Ctrl/Cmd+,`) opens the preferences window.

**There is no OK button.** Every change is saved and applied as you make it — the
open windows follow along while the settings window is still up. Two settings are
labelled *takes effect at the next launch*, because they describe what HouseGraph
does while it is starting.

**Restore Defaults**, at the bottom, puts every setting on every tab back to how a
fresh install starts.

## General

| Setting | Does |
| --- | --- |
| **Graph folder** | Where **Open** and **Save** start. |
| **Start file dialogs in the folder last used** | Reopens each dialog wherever you last saved or opened a graph, instead of in the graph folder. |
| **Entries to keep** | How many graphs **File ▸ Open Recent** lists (1–50). |
| **Reopen the last graph on launch** | Off means HouseGraph always starts on an empty canvas. |
| **Restore the last window size** | Off means every window opens at the default size. |

### Moving your graphs

The graph folder is the **only** HouseGraph directory you can move. Everything else —
your encrypted secrets, installed node libraries, logs — stays under the app's own
data directory.

That is deliberate. `HOUSEGRAPH_HOME` moves *everything*, so pointing it at a synced
folder would put your secret key in Dropbox along with your graphs. This setting moves
the graphs alone.

Choosing a folder creates it if it does not exist. If the folder later goes away — an
unplugged drive, a share that did not mount — HouseGraph logs a warning and falls back
to the default for that session. The setting is left alone, so your folder comes back
when the drive does.

**Moving the folder does not move the graphs already in the old one.** Copy them
across yourself; nothing is deleted either way.

## Editor

**Watch speed** sets the pace a new window runs at — the pause the engine takes
between flow steps, so a cascade can be followed by eye instead of finishing in a
blink. Changing it applies to every open window too. **Run ▸ Watch Speed** still
overrides it for one window, and no watch speed is ever saved into a graph.

## Logging

**Output levels** are the same per-output levels the log window's toolbar offers —
change either and the other follows. **External Destination…** opens the same dialog
as the log window's **External…**, for forwarding records to a Discord webhook.

| Setting | Does |
| --- | --- |
| **Roll over at (MB)** | How large `housegraph.log` grows before it rolls over. Lowering it below the file's current size rolls it immediately, which is how you reclaim the disk. |
| **Generations to keep** | How many rolled-over log files are kept. `0` keeps none. |
| **Records to retain** | How much history the log window holds. Lowering it discards the oldest records straight away. |
| **Follow new records** | Whether the log window scrolls to the newest line as it arrives. |

## Node Libraries

**Warn before installing a node library** switches the trust-on-first-use warning back
on. A node library runs with the same privileges as HouseGraph itself, and the warning
shown before one is downloaded has a *don't show this again* box — this is the only
place to undo that.

What an unattended daemon may fetch and run is **not** configured here. It lives in
`config/remote.json`, written by hand, and it is the trust boundary for a machine
nobody is sitting at: see [server-setup.md](server-setup.md).

## Where the settings are stored

`config/preferences.json` under the app data directory (**Tools ▸ Open Data Folder**).
Plain JSON, safe to read, and forgiving if you edit it: a value HouseGraph cannot make
sense of falls back to its default rather than stopping the app from starting.

---

**When you change this, update…** this file whenever a setting is added, removed, or
changes what it does. The keys behind them are listed in
[`../engine/storage.md`](../engine/storage.md), and how a change reaches the running
app is in [`../engine/ui-layer.md`](../engine/ui-layer.md).
