/**
 * The application menu bar.
 *
 * <p>{@link io.github.jaymcole.housegraph.ui.menu.MainMenuBar} builds the menus and owns every
 * label, accelerator and enablement rule;
 * {@link io.github.jaymcole.housegraph.ui.menu.MenuActions} is the half of the commands only the
 * host application can carry out — opening a file, showing a window, quitting. Canvas commands are
 * called straight on {@code GraphCanvas} and are not part of that interface.
 */
package io.github.jaymcole.housegraph.ui.menu;
