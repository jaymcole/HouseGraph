/**
 * IP-camera discovery and clients.
 * <p>
 * {@link io.github.jaymcole.housegraph.camera.CameraDiscovery} finds cameras via
 * ONVIF WS-Discovery (with a TCP port-scan fallback);
 * {@link io.github.jaymcole.housegraph.camera.OnvifEnrichment} adds authenticated
 * ONVIF details; {@link io.github.jaymcole.housegraph.camera.ReolinkClient} reads
 * detection state from Reolink's HTTP CGI API; and
 * {@link io.github.jaymcole.housegraph.camera.CameraConfigStore} persists the
 * (credential-free) camera registry. Pure JDK — no camera SDK.
 * <p>
 * See {@code docs/architecture/integrations.md}.
 */
package io.github.jaymcole.housegraph.camera;
