package io.github.jaymcole.housegraph.ui;

import io.github.jaymcole.housegraph.graph.BaseNode;

import java.util.function.Consumer;

/**
 * The host application's side of "add a node referencing a module".
 *
 * <h2>Why the canvas does not do this itself</h2>
 * Every other entry in the context menu is a node <em>type</em>, and
 * {@code NodeRegistry.discover()} hands the canvas the whole list. Modules are not types: there is
 * one {@code ModuleNode} class and any number of module files, so offering them means reading the
 * filesystem, refreshing an index and resolving an id — none of which belongs in a canvas widget,
 * and all of which has to happen off the FX thread. So the canvas contributes the menu item and the
 * placement, and the application contributes the answer, exactly the way {@code MenuActions} splits
 * the menu bar.
 *
 * @see io.github.jaymcole.housegraph.ui.menu.MenuActions
 */
@FunctionalInterface
public interface ModuleReferenceAction {

    /**
     * Asks the user which module to reference and hands back a node bound to it.
     *
     * <p>Called on the FX Application Thread, and expected to return immediately: an implementation
     * that has to scan the filesystem does so on a worker and calls {@code place} back on the FX
     * thread when it has an answer. Not calling {@code place} at all is how a cancelled pick, or a
     * machine with no modules on it, is reported — there is nothing for the canvas to do either way.
     *
     * @param place accepts the node to put on the canvas; already aimed at the point the menu was
     *              opened at, so an implementation never has to know where that was
     */
    void chooseModule(Consumer<BaseNode> place);
}
