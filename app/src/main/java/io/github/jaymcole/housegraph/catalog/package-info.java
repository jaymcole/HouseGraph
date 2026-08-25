/**
 * The machine-readable node catalog: what an external harness reads instead of the Add-Node menu.
 * <p>
 * {@link io.github.jaymcole.housegraph.catalog.NodeCatalog} describes every node type a
 * {@code NodeRegistry} can discover — id, category, kind, owning library, and typed inputs,
 * outputs and flow ports — as the versioned JSON the {@code nodes} CLI command exports.
 * {@link io.github.jaymcole.housegraph.catalog.NodeSignature} fingerprints one node type's shape so
 * a save file can carry evidence of what it was built against, and
 * {@link io.github.jaymcole.housegraph.catalog.SchemaDriftCheck} compares that evidence to what is
 * currently installed.
 * <p>
 * Unlike {@code search}, this package does construct nodes — reading ports needs an instance — so
 * it exists only for on-demand, explicit invocations, never as a side effect of something implicit.
 * <p>
 * See {@code docs/engine/save-format.md} and {@code docs/engine/schema/}.
 */
package io.github.jaymcole.housegraph.catalog;
