/**
 * Running HouseGraph unattended: syncing graphs from git, and keeping them running.
 *
 * <p>Everything here is headless and has no JavaFX dependency, for the same reason the
 * {@code plugin} package doesn't — this repository has no way to test a window, so nothing worth
 * testing may live in one. The JavaFX part of a deployment is the child process, not this.
 *
 * <p>The pieces, lowest first:
 * <ul>
 *   <li>{@link io.github.jaymcole.housegraph.remote.GitCommand} — runs the git binary, keeping
 *       credentials out of {@code argv}.</li>
 *   <li>{@link io.github.jaymcole.housegraph.remote.GraphRepository} — one tracked repository: poll
 *       the remote, mirror it locally. A mirror, never a working copy.</li>
 *   <li>{@link io.github.jaymcole.housegraph.remote.RemoteConfig} — the operator's file, and the
 *       only place a repository URL is trusted from.</li>
 *   <li>{@link io.github.jaymcole.housegraph.remote.RepoManifest} — {@code housegraph.json} in a
 *       synced repository: which graphs to run, which libraries they need.</li>
 *   <li>{@link io.github.jaymcole.housegraph.remote.RemoteState} — the last commit deployed, so a
 *       restart doesn't look like a change.</li>
 *   <li>{@link io.github.jaymcole.housegraph.remote.RemoteDeployment} — decides what should run.</li>
 *   <li>{@link io.github.jaymcole.housegraph.remote.Supervisor} and
 *       {@link io.github.jaymcole.housegraph.remote.GraphProcess} — keep it running.</li>
 *   <li>{@link io.github.jaymcole.housegraph.remote.ExitCodes} — how a child asks for a restart.</li>
 * </ul>
 *
 * <p>Full design: {@code docs/architecture/deployment.md}.
 */
package io.github.jaymcole.housegraph.remote;
