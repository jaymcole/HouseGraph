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
 *   <li>{@link io.github.jaymcole.housegraph.plugin.PluginTrust} — which repositories may install
 *       without asking, persisted to {@code config/plugin-trust.json}. Both of its gates default to
 *       off, and the list is only ever written from a confirmation the user saw.</li>
 *   <li>{@link io.github.jaymcole.housegraph.plugin.RepositoryUrls} — the URL comparison shared with
 *       the daemon's allowlist, so the two cannot disagree on what the same repository is.</li>
 *   <li>{@link io.github.jaymcole.housegraph.plugin.AutoInstallPlan} — the pure install/update/ask
 *       decision over a dependency report.</li>
 *   <li>{@link io.github.jaymcole.housegraph.plugin.GitHubReleases} — latest-release lookup, shaped
 *       around GitHub's 60-requests-per-hour unauthenticated limit.</li>
 *   <li>{@link io.github.jaymcole.housegraph.plugin.PluginInstaller} — download, validate, hash,
 *       record; plus pruning superseded versions at startup.</li>
 *   <li>{@link io.github.jaymcole.housegraph.plugin.PluginLoader} — one shared, parent-first class
 *       loader, and the scan roots {@code NodeRegistry} works from.</li>
 * </ul>
 *
 * <p><b>Nothing here loads a library over the network at startup.</b> The loader reads only the local
 * catalog and the cached jars. A library whose jar has gone missing doesn't stop a graph opening —
 * its nodes become placeholders that preserve the user's work.
 *
 * <p>Every network operation is user-initiated, with one qualification since auto-install:
 * {@link io.github.jaymcole.housegraph.plugin.PluginInstaller#apply} may run during a startup reopen
 * without anyone clicking. That is still a user decision, just an earlier one — it happens only for a
 * repository the user accepted in the install dialog, with the master switch they turned on. Both
 * default to off, so an untouched installation behaves exactly as before.
 *
 * <p>See {@code docs/architecture/plugins.md}, including the honest threat model: a node library is
 * arbitrary code with the user's full privileges, and nothing here is a sandbox.
 */
package io.github.jaymcole.housegraph.plugin;
