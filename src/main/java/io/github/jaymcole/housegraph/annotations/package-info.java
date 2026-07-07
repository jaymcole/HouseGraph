/**
 * Runtime annotations that mark node classes for discovery and display.
 * <p>
 * {@link io.github.jaymcole.housegraph.annotations.Display.Name} sets a node's
 * user-facing label; {@link io.github.jaymcole.housegraph.annotations.Node.Disabled}
 * hides a node type from the Add-Node menu while keeping it loadable. Both are read
 * reflectively by {@code NodeRegistry} and {@code BaseNode}.
 */
package io.github.jaymcole.housegraph.annotations;
