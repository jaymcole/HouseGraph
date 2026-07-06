package io.github.jaymcole.housegraph.camera;

/**
 * One camera found on the network. The {@code mac} is the stable identity (burned into
 * the hardware, so it survives reboots and DHCP changes — unlike the IP); it may be null
 * if the camera couldn't be resolved in the ARP cache (e.g. not on the same L2 subnet).
 * {@code name}/{@code model} are best-effort from the ONVIF discovery scopes.
 */
public record DiscoveredCamera(String ip, String mac, String name, String model, boolean reolink) {

    /** The best human-readable label available, falling back to the IP. */
    public String label() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (model != null && !model.isBlank()) {
            return model;
        }
        return ip;
    }
}
