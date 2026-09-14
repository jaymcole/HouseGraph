/**
 * The preferences window, and the settings it edits.
 * <p>
 * {@link io.github.jaymcole.housegraph.ui.settings.AppSettings} is the model: every remembered
 * choice as one immutable value, with the keys, the defaults and the clamping stated once. It is
 * free of JavaFX — like {@code ui.io.RecentGraphs} — so loading, saving and the range checks are
 * unit-testable headlessly.
 * {@link io.github.jaymcole.housegraph.ui.settings.SettingsWindow} is the dialog over it.
 * <p>
 * The rule this package exists to keep is that <b>a saved setting is applied to the running app,
 * not only to the next launch</b>. {@code AppSettings.applyGlobally()} covers what is reachable
 * process-wide (the graph folder, the log outputs); {@code App.applySettings} covers what belongs
 * to a window. A setting added here needs a home in one of those two, or a note in the dialog
 * saying which launch it takes effect on.
 * <p>
 * When you add a setting, update {@code docs/engine/storage.md}'s key list and — if it changes what
 * a user sees — {@code docs/guides/}.
 */
package io.github.jaymcole.housegraph.ui.settings;
