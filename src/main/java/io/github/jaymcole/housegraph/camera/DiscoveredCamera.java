package io.github.jaymcole.housegraph.camera;

import java.util.List;

/**
 * One camera found on the network — a faithful port of the AnimalNotifier
 * {@code DiscoveredCamera}. The {@code mac} is the stable identity (burned into the hardware,
 * so it survives reboots and DHCP changes — unlike the IP); it may be null if the camera
 * couldn't be resolved in the ARP cache (e.g. not on the same L2 subnet).
 * <p>
 * {@code scopes} are the raw ONVIF scope URIs the camera advertises (name/hardware/
 * manufacturer), from which {@link #name()}, {@link #hardware()}, {@link #manufacturer()} and
 * {@link #reolink()} are derived. {@code customName} and {@code model} are the good values —
 * the app-set name and the clean model string — but they can only be read from an
 * <em>authenticated</em> ONVIF request (see {@link OnvifEnrichment}), so they are null until a
 * camera has been enriched with a password.
 */
public record DiscoveredCamera(String ip, String mac, List<String> scopes, String customName, String model) {

    public DiscoveredCamera {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    /** A camera straight from network discovery: scopes only, not yet authenticated-enriched. */
    public DiscoveredCamera(String ip, String mac, List<String> scopes) {
        this(ip, mac, scopes, null, null);
    }

    /** Returns a copy with its MAC resolved (discovery finds the IP first, the MAC second). */
    public DiscoveredCamera withMac(String mac) {
        return new DiscoveredCamera(ip, mac, scopes, customName, model);
    }

    /**
     * Returns a copy carrying the values an authenticated ONVIF request produced: the app-set
     * {@code customName}, the clean {@code model}, and the authoritative {@code scopes} (which
     * replace any unauthenticated/port-scan placeholder). Nulls leave that field as it was.
     */
    public DiscoveredCamera enriched(String customName, String model, List<String> scopes) {
        return new DiscoveredCamera(ip, mac,
                scopes != null && !scopes.isEmpty() ? scopes : this.scopes,
                customName != null ? customName : this.customName,
                model != null ? model : this.model);
    }

    /** The name in the ONVIF {@code name} scope (for Reolink, the manufacturer — not the app-set name). */
    public String name() {
        return scopeValue("name");
    }

    /** The model in the ONVIF {@code hardware} scope. */
    public String hardware() {
        return scopeValue("hardware");
    }

    /** The manufacturer from the ONVIF {@code manufacturer}/{@code mfr} scope. */
    public String manufacturer() {
        String value = scopeValue("manufacturer");
        return value != null ? value : scopeValue("mfr");
    }

    /** Whether this device identifies as a Reolink across any of its name/hardware/manufacturer scopes. */
    public boolean reolink() {
        String blob = String.join(" ",
                orEmpty(name()), orEmpty(hardware()), orEmpty(manufacturer())).toLowerCase();
        return blob.contains("reolink");
    }

    /** The best human-readable label: the app-set name, else the model, else the ONVIF hardware/scope name. */
    public String displayName() {
        return firstNonBlank(customName, model, hardware(), name());
    }

    /** The best label available for the registry/UI, falling back to the IP. */
    public String label() {
        String display = displayName();
        return display != null ? display : ip;
    }

    /** The value after {@code /<key>/} in an ONVIF scope (e.g. {@code onvif://.../name/Reolink} -> {@code Reolink}). */
    private String scopeValue(String key) {
        String prefix = "/" + key + "/";
        for (String scope : scopes) {
            int idx = scope.indexOf(prefix);
            if (idx != -1) {
                return trimSlashes(scope.substring(idx + prefix.length()));
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String trimSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }
}
