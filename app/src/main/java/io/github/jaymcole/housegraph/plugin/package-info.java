/**
 * The host side of out-of-tree node libraries: reading what a library declares, fetching it from
 * GitHub, recording what is installed, and serving its classes.
 *
 * <p>This package lives in {@code app} and is deliberately <em>not</em> published — a node library
 * compiles against {@code housegraph-api} and never sees any of this. What it must not do is import
 * JavaFX: everything here is headless so it can be tested, since this repository has no
 * infrastructure for testing JavaFX windows. The dependency window is a thin shell over these
 * classes for exactly that reason.
 *
 * <ul>
 *   <li>{@link io.github.jaymcole.housegraph.plugin.PluginManifest} — a library's self-description,
 *       readable from a jar without loading a class from it.</li>
 *   <li>{@link io.github.jaymcole.housegraph.plugin.PluginCatalog} — what is installed, persisted to
 *       {@code config/plugins.json} with atomic writes.</li>
 *   <li>{@link io.github.jaymcole.housegraph.plugin.GitHubReleases} — latest-release lookup, shaped
 *       around GitHub's 60-requests-per-hour unauthenticated limit.</li>
 *   <li>{@link io.github.jaymcole.housegraph.plugin.PluginInstaller} — download, validate, hash,
 *       record; plus pruning superseded versions at startup.</li>
 *   <li>{@link io.github.jaymcole.housegraph.plugin.PluginLoader} — one shared, parent-first class
 *       loader, and the scan roots {@code NodeRegistry} works from.</li>
 * </ul>
 *
 * <p><b>No startup path here makes a network call.</b> The loader reads only the local catalog and
 * the cached jars. Every network operation is user-initiated. A library whose jar has gone missing
 * doesn't stop a graph opening — its nodes become placeholders that preserve the user's work.
 *
 * <p>See {@code docs/architecture/plugins.md}, including the honest threat model: a node library is
 * arbitrary code with the user's full privileges, and nothing here is a sandbox.
 */
package io.github.jaymcole.housegraph.plugin;
