package io.github.jaymcole.housegraph.ui.menu;

import java.io.File;
import java.util.List;

/**
 * The host application's side of the menu bar.
 *
 * <h2>Why this exists</h2>
 * {@link MainMenuBar} can build every canvas command itself — undo, zoom, select-all are all methods
 * on {@code GraphCanvas}. The rest are not: opening a file, writing one, showing a window, quitting
 * are things only the application knows how to do, and they need the stage, the preferences store
 * and the plugin catalog that live there. This interface is that half, so the menu bar depends on a
 * dozen named commands rather than on {@code App} — which would be a cycle, since {@code App}
 * constructs the menu bar.
 *
 * <p>Everything here runs on the FX Application Thread, and may open a modal dialog.
 *
 * <h2>One implementation per window</h2>
 * These are the commands of <em>this</em> menu bar's window: {@link #newGraph()} empties this
 * canvas, {@link #saveGraph()} writes this window's file, {@link #closeWindow()} closes this window.
 * The three that are inherently app-wide say so — {@link #newWindow()} and
 * {@link #openGraphInNewWindow()} open another window, and {@link #exit()} quits everything.
 */
public interface MenuActions {

    /** Discards the graph in this window and starts an empty one here. Owns its own confirmation. */
    void newGraph();

    /** Opens another editor window, on an empty canvas. */
    void newWindow();

    /** Prompts for a file and opens it in this window, replacing what is on the canvas. */
    void openGraph();

    /**
     * Prompts for a file and opens it in a window of its own, leaving this one alone.
     *
     * <p>A file already open in some window raises that window rather than opening a second view of
     * it: each window saves its whole canvas, so two on one file would race to overwrite each other.
     */
    void openGraphInNewWindow();

    /** The files most recently saved or opened, newest first; may include files that no longer exist. */
    List<File> recentGraphs();

    /** Opens a file chosen from the recent list — the same path {@link #openGraph()} ends in. */
    void openRecentGraph(File file);

    /** Forgets the recent list. */
    void clearRecentGraphs();

    /** Writes to the current file, falling back to {@link #saveGraphAs()} when there isn't one yet. */
    void saveGraph();

    /** Prompts for a destination and writes there, which then becomes the current file. */
    void saveGraphAs();

    /** Whether a file has been chosen, i.e. whether {@link #saveGraph()} would write without prompting. */
    boolean hasCurrentFile();

    /** Closes this window, disposing its graph. Closing the last one quits, same as {@link #exit()}. */
    void closeWindow();

    /**
     * Writes the canvas into the modules directory and gives it a stable id, so other graphs can
     * reference it as a single node. Prompts for the destination, and reports what it did.
     *
     * <p>An explicit command rather than something File ▸ Save works out for itself: a graph that
     * happens to contain a boundary marker is not necessarily meant to be a reusable module, and an
     * id, once other graphs reference it, cannot be taken back.
     */
    void publishAsModule();

    /** Prompts for a directory and writes one PNG per distinct graph on the canvas into it. */
    void exportImages();

    /** Quits the app, every window included. */
    void exit();

    /** Opens the secrets editor. */
    void editSecrets();

    /** Opens the node-library manager. */
    void manageNodeLibraries();

    /** Opens the log window. */
    void showLogs();

    /** Reveals HouseGraph's data directory in the desktop's file browser. */
    void openDataFolder();

    /** Opens the project's documentation in the desktop's browser. */
    void openDocumentation();

    /**
     * Sets the pause the engine takes between flow steps, in milliseconds; 0 is off. Session-only —
     * see {@code NodeGraph.setStepDelayMillis}.
     */
    void setStepDelayMillis(long millis);
}
