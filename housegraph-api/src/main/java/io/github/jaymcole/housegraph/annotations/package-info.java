/**
 * Runtime annotations that mark node classes for discovery, display and search.
 * <p>
 * {@link io.github.jaymcole.housegraph.annotations.Display.Name} sets a node's
 * user-facing label; {@link io.github.jaymcole.housegraph.annotations.Node.Disabled}
 * hides a node type from the Add-Node menu while keeping it loadable;
 * {@link io.github.jaymcole.housegraph.annotations.Node.Type} gives a node a stable
 * save-file id decoupled from its class name.
 * <p>
 * {@link io.github.jaymcole.housegraph.annotations.Display.Description},
 * {@link io.github.jaymcole.housegraph.annotations.Node.Keywords} and
 * {@link io.github.jaymcole.housegraph.annotations.Node.Kind} (whose values are
 * {@link io.github.jaymcole.housegraph.annotations.NodeKind}) describe a node well enough
 * to be <em>found</em> by someone who does not know its name.
 * <p>
 * All are read reflectively, by {@code NodeRegistry}, {@code NodeMetadata} and
 * {@code BaseNode} — never by instantiating the node. See {@code docs/engine/node-search.md}.
 */
package io.github.jaymcole.housegraph.annotations;
