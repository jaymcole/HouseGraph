# `ui/` — the JavaFX layer

Full context: [`docs/architecture/ui.md`](../../../../../../../../docs/architecture/ui.md).
Start at the repo-root [`CLAUDE.md`](../../../../../../../../CLAUDE.md) if you haven't.

This is the only package that owns JavaFX-thread concerns. It sits at the top of
the dependency stack: it depends on `graph/` (and below), never the reverse.

Hold these when editing here:

- **All view code runs on the FX Application Thread.** The engine runs passes on
  background threads and marshals its callbacks to the UI via its callback
  executor, which `GraphCanvas` sets to `Platform::runLater`. Never call into
  JavaFX from an engine thread, and never call blocking work on the FX thread
  (do it on a worker thread, then `Platform.runLater` the UI update — see
  `DiscordBotNode`'s connect flow).
- **Reversible canvas mutations are `Command`s.** Anything the user can undo goes
  through `UndoManager` as a `Command` (`execute()`/`undo()`), not an ad-hoc
  mutation. Use `record()` for gestures applied live (e.g. a drag) that become one
  undo step at the end.
- **Keep save/load logic headless.** `GraphFileIO`'s `toJson`/`fromJson` must stay
  free of JavaFX so they can be unit-tested; only `save`/`load` touch a canvas.
  When you change the JSON format, keep the forgiving-read/back-compat behavior and
  update the `GraphFileIO` Javadoc **and** the ui doc.
- **New manually-editable type?** Add one line to the `ValueEditors` static block —
  nothing else changes — and note it in the ui doc.

**When you change canvas interaction, views, commands, editors, or the save
format, update [`docs/architecture/ui.md`](../../../../../../../../docs/architecture/ui.md).**
