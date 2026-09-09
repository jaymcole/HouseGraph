# Editor windows

`GraphWindow` is one editor window: a `Stage`, the `NodeGraph` open in it, the
`GraphCanvas` drawing that graph, the file it saves to, and its own
[`MainMenuBar`](ui-layer.md#menu-bar). `App` owns the list of them and the services
they share — the preferences store, the node-library catalog and loader, the
`NodeRegistry`, the `ModuleLibrary`.

## Where the line falls

Whatever a person can have several of at once lives on the window; whatever there is
exactly one of per process stays on `App`. So a second window costs a second graph and
a second canvas, not a second copy of the application: a node library is scanned once
and a module index built once however many windows are open.

`App` reaches into a window only for the questions that must span all of them:

| Question | Why it spans windows |
| --- | --- |
| `countLiveNodesFrom(pluginId)` | Sums across windows — a library is in use if any canvas holds one of its nodes |
| `tryReloadNodeLibraries()` | Refused while *any* canvas holds a live library node; one window's stale `Class` binding is as wrong as another's, and a reload rebuilds the loader for every library at once |
| `rememberOpenedFile(file)` | There is one "last file" and one recent list; the newest save or open in any window wins |

## Documents, not tabs

Each window has its own undo history, missing-library notice and Watch Speed.
**File ▸ Save** writes the file of the window whose menu was opened, and the window
title carries that file's name. The copy/paste clipboard is a `GraphCanvas` field, so
it is per-window too — copying between windows is not something the canvas supports.

What windows share, besides the services above, is `ResourceRegistry.shared()`. A
long-lived resource is referenced by name rather than wired
([long-lived-resources.md](../nodes/long-lived-resources.md)), and that registry is
process-wide, so one window's camera node is reachable from another window's
listener.

**Opening a file already open somewhere raises that window** rather than opening a
second view of it. Each window saves its whole canvas, so two on one file would race
to overwrite each other.

## Lifetime

The primary stage JavaFX supplies becomes the first window; every one after it gets a
fresh `Stage`, and nothing else distinguishes them. A new window is stepped down and
right of the one opened most recently, measured from that window's actual position —
an unshown stage's `x` and `y` are `NaN` until the platform places it.

`stage.setOnHidden` is what disposes a window's graph. Two things follow from putting
it there rather than on a close request:

- A closed window's timers, connections and child processes go away with it, while
  every other window keeps running.
- A graph is disposed exactly once whether the window was closed by hand or hidden by
  `Platform.exit()`.

## Unsaved changes

`GraphCanvas.hasUnsavedChanges()` is backed by the same `UndoManager` that drives
Undo/Redo (`ui/command`): the undo stack's depth is a position in the graph's linear
edit history, and the canvas is dirty whenever that position has moved since
`markSaved()` was last called. `markSaved()` runs wherever a window takes on a new
current file — after a save, after a load, after publishing as a module — and
`UndoManager.clear()` (New, and the reload half of opening a file) resets the saved
position to zero along with the history itself, so a fresh or just-opened graph is
never dirty. Everything undoable is, by construction, everything a save would need to
capture — a value edited through an inline node control rather than a canvas gesture
is the one kind of change this cannot see, same as it is invisible to Undo.

`GraphWindow.confirmClose()` is what asks: nothing when the canvas is clean, otherwise
Save / Don't Save / Cancel, and Save re-checks `hasUnsavedChanges()` afterward so a
cancelled Save As or a failed write still blocks the close rather than discarding
silently. This is also where `stage.setOnCloseRequest` differs from `setOnHidden`
above: a system close button fires a close-request event that `confirmClose()` can
veto by consuming it, but `Stage.close()` does not fire that event at all — so `close()`
calls `confirmClose()` itself before ever touching the stage, and so does `App.exit()`,
once per still-open window, before its one `Platform.exit()` call that would otherwise
hide every window with no chance to ask.

Disposal runs on the FX thread, because `NodeGraph.dispose`'s first pass —
`onRemoved()` per node — is thread-affine: that is what lets a node stop a `Timeline`
or reset a control. Its second pass waits on `releaseResources()`, up to
`NodeGraph.DEFAULT_RELEASE_TIMEOUT`, so closing a window whose graph holds a
slow-releasing node (a server draining connections, say) stalls the *other* windows
for as long as that takes. Accepted rather than worked around: the alternative is
running `onRemoved()` off the FX thread, which the hook's contract forbids, and a
window is closed deliberately.

Closing the last window quits, and `App` does that explicitly rather than leaving it
to JavaFX's implicit exit — an open **Logs** window would otherwise keep a windowless
app alive. `App.stop` disposes whatever is still open, for the paths that never hide a
stage, and a second dispose of an already-emptied graph is harmless. The shutdown
budget does not scale with the number of windows: each graph's release pass is already
concurrent, and graphs are disposed in turn.

`--graph=<path>` still names one file, opened in the first window, and still keeps
this run out of `LAST_FILE` and the recent list — see the `App` Javadoc and
[remote-runtime.md](remote-runtime.md).

---

**When you change this, update…** this file whenever you change how editor windows
are opened, closed, positioned or torn down, or move something across the line
between `App` and `GraphWindow`. Menu wording and accelerators belong in
[ui-layer.md](ui-layer.md); the launch and shutdown sequence in
[architecture.md](architecture.md).
