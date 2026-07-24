/**
 * Local web-hosting integration.
 * <p>
 * {@link io.github.jaymcole.housegraph.web.LocalWebServer} serves a directory of static
 * files over the JDK's built-in HTTP server and advertises it on the LAN as
 * {@code <name>.local} via jmdns multicast DNS — the long-lived resource behind a
 * web-server node (see {@code graph.nodes.web.WebServerNode}).
 * <p>
 * {@link io.github.jaymcole.housegraph.web.NodeProcessServer} is its Node.js sibling: instead of
 * serving files from the JVM it launches an external Node server (an Express app, a Vite dev
 * server, {@code npm start}) as a child process, streams its output to the log, and advertises the
 * same {@code <name>.local} — the resource behind {@code graph.nodes.web.NodeServerNode}. Both
 * share {@link io.github.jaymcole.housegraph.web.LanAddress} for picking the mDNS advertise address.
 * <p>
 * See {@code docs/architecture/integrations.md}.
 */
package io.github.jaymcole.housegraph.web;
