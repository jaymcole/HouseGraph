/**
 * Inline value editing: turning a node's manually-editable variables and secrets into
 * on-canvas fields.
 * <p>
 * {@link io.github.jaymcole.housegraph.ui.editor.ValueEditors} maps a value type to a
 * parse/format pair, so a {@code NodeVariable}'s {@code PortView} gets an inline text field
 * when the variable is manually editable and its type is registered here. Adding a type is a
 * one-line change to the {@code ValueEditors} static block.
 * {@link io.github.jaymcole.housegraph.ui.editor.SecretsEditor} is the dialog for choosing or
 * entering a secret (which is stored by reference, never inlined). See {@code docs/architecture/ui.md}.
 */
package io.github.jaymcole.housegraph.ui.editor;
