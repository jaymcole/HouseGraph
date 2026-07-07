package io.github.jaymcole.housegraph.graph.nodes.camera;

import io.github.jaymcole.housegraph.camera.CameraConfigStore;
import io.github.jaymcole.housegraph.camera.CameraConfigStore.KnownCamera;
import io.github.jaymcole.housegraph.camera.CameraDiscovery;
import io.github.jaymcole.housegraph.camera.DiscoveredCamera;
import io.github.jaymcole.housegraph.camera.ReolinkClient.ReolinkException;
import javafx.scene.control.ComboBox;
import javafx.util.StringConverter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reusable "which camera?" control for the camera nodes: it renders a dropdown of the
 * cameras known to {@link CameraConfigStore}, remembers the chosen one across saves (by its
 * stable MAC, not its label or IP, both of which can change), and resolves that choice to a
 * reachable host at run time. Any Reolink node can compose one of these instead of exposing
 * a raw host field — a node holds a {@code CameraSelector}, forwards its
 * {@link #saveState()}/{@link #loadState(Map)} and drops {@link #buildComboBox()} into its
 * UI, then wraps its actual work in {@link #withHost(HostAction)}.
 * <p>
 * The selected MAC is written on the UI thread and read on the execution thread, hence
 * {@code volatile}.
 */
public final class CameraSelector {

    /** How long to listen during a rediscovery sweep when the cached IP has gone stale. */
    private static final int DISCOVERY_TIMEOUT_SECONDS = 4;

    private static final String STATE_KEY = "camera";

    private volatile String selectedMac;

    /** An action run against a resolved camera host (e.g. a Reolink poll); see {@link #withHost}. */
    @FunctionalInterface
    public interface HostAction<T> {
        T apply(String host);
    }

    // --- persistence (fold into the owning node's saveState/loadState) ---------------

    public Map<String, String> saveState() {
        return selectedMac == null ? Map.of() : Map.of(STATE_KEY, selectedMac);
    }

    public void loadState(Map<String, String> state) {
        String mac = state.get(STATE_KEY);
        selectedMac = (mac == null || mac.isBlank()) ? null : mac;
    }

    /** The currently selected camera, read fresh from the store so its IP/label stay current. */
    public Optional<KnownCamera> selected() {
        return selectedMac == null ? Optional.empty() : CameraConfigStore.find(selectedMac);
    }

    // --- UI --------------------------------------------------------------------------

    /**
     * Builds the camera dropdown. Its items are refreshed from the store each time it's
     * opened (a camera may have been discovered since), and the persisted selection is
     * re-matched by MAC so it survives label/IP changes.
     */
    public ComboBox<KnownCamera> buildComboBox() {
        ComboBox<KnownCamera> box = new ComboBox<>();
        box.setPromptText("Select camera…");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(KnownCamera camera) {
                return camera == null ? "" : camera.label();
            }

            @Override
            public KnownCamera fromString(String string) {
                return null;
            }
        });
        refreshItems(box);
        box.setOnShowing(event -> refreshItems(box));
        box.valueProperty().addListener((observable, previous, chosen) -> {
            if (chosen != null) {
                selectedMac = chosen.mac();
            }
        });
        return box;
    }

    private void refreshItems(ComboBox<KnownCamera> box) {
        List<KnownCamera> cameras = CameraConfigStore.list();
        box.getItems().setAll(cameras);
        if (selectedMac != null) {
            cameras.stream()
                    .filter(camera -> camera.mac().equals(selectedMac))
                    .findFirst()
                    .ifPresent(box::setValue);
        }
    }

    // --- host resolution with stale-IP recovery --------------------------------------

    /**
     * Runs {@code action} against the selected camera's last known IP. If that fails with a
     * {@link ReolinkException} — the camera may simply be offline, or its DHCP lease may
     * have moved it — this rediscovers the camera by MAC; if it's found at a <em>different</em>
     * address, the new IP is cached and the action is retried once there. Any other failure,
     * or no camera selected / no known IP, surfaces as a {@link ReolinkException} for the
     * node to report.
     * <p>
     * The rediscovery sweep is only attempted on failure, but it is not free (a multicast
     * wait, possibly a port scan), so a persistently offline camera makes each poll slow.
     */
    public <T> T withHost(HostAction<T> action) {
        KnownCamera camera = selected().orElseThrow(() -> new ReolinkException(selectedMac == null
                ? "No camera selected."
                : "Selected camera " + selectedMac + " is no longer in the registry."));
        String host = requireIp(camera);
        try {
            return action.apply(host);
        } catch (ReolinkException first) {
            String fresh = rediscover(camera.mac());
            if (fresh == null || fresh.equals(host)) {
                throw first; // couldn't relocate it, or it hasn't moved - the original error stands
            }
            return action.apply(fresh);
        }
    }

    /**
     * Rediscovers the camera with this MAC on the local network, caching and returning its
     * current IP, or null if it wasn't found. Public so a node can offer a manual "relocate"
     * action; {@link #withHost} calls it automatically on failure.
     */
    public String rediscover(String mac) {
        for (DiscoveredCamera found : CameraDiscovery.discover(DISCOVERY_TIMEOUT_SECONDS)) {
            if (mac.equalsIgnoreCase(found.mac())) {
                CameraConfigStore.updateIp(mac, found.ip());
                return found.ip();
            }
        }
        return null;
    }

    private static String requireIp(KnownCamera camera) {
        if (camera.lastKnownIp() == null || camera.lastKnownIp().isBlank()) {
            throw new ReolinkException("Camera " + camera.label() + " has no known IP; run Discover Cameras.");
        }
        return camera.lastKnownIp();
    }
}
