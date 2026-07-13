/**
 * Runtime annotations that mark node classes for discovery and display.
 * <p>
 * {@link io.github.jaymcole.housegraph.annotations.Display.Name} sets a node's
 * user-facing label; {@link io.github.jaymcole.housegraph.annotations.Node.Disabled}
 * hides a node type from the Add-Node menu while keeping it loadable;
 * {@link io.github.jaymcole.housegraph.annotations.Node.Type} gives a node a stable
 * save-file id decoupled from its class name. All are read reflectively by
 * {@code NodeRegistry} and {@code BaseNode}.
 */
package io.github.jaymcole.housegraph.annotations;
