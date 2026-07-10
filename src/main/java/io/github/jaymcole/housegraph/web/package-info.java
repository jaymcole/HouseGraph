/**
 * Local web-hosting integration.
 * <p>
 * {@link io.github.jaymcole.housegraph.web.LocalWebServer} serves a directory of static
 * files over the JDK's built-in HTTP server and advertises it on the LAN as
 * {@code <name>.local} via jmdns multicast DNS — the long-lived resource behind a
 * web-server node (see {@code graph.nodes.web.WebServerNode}).
 * <p>
 * See {@code docs/architecture/integrations.md}.
 */
package io.github.jaymcole.housegraph.web;
