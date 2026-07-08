package io.github.jaymcole.housegraph.graph.nodes.iot;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Pushes a status to the squirrel-alarm IoT device — an Arduino UNO R4 WiFi driving an
 * LED matrix (see {@code extras/squirrel_status}). When triggered it sends an HTTP GET
 * to {@code http://<host>/<status>}; the device plays the matching animation
 * ({@code bird}, {@code squirrel}) or blanks the screen ({@code clear}), auto-reverting
 * after ~30s.
 * <p>
 * This is the action side of the pattern (like {@code DiscordSendMessageNode}): control
 * flows straight through, so more work can be chained after it. Both inputs can be typed
 * in place or wired — type a fixed {@code Status} for a hard-wired alarm, or wire one in
 * so upstream logic (a camera detection, a Discord command) decides what the sign shows.
 * <p>
 * The device advertises itself over mDNS as {@code squirrel-alarm.local}; if that name
 * doesn't resolve on this machine, put the device's IP in the {@code Host} field instead.
 */
@Display.Name("Squirrel Alarm")
public class SquirrelAlarmNode extends BaseNode {

    private static final String DEFAULT_HOST = "squirrel-alarm.local";

    private final NodeVariable<String> host = new NodeVariable<>("Host", String.class, true);
    private final NodeVariable<String> status = new NodeVariable<>("Status", String.class, true).required();
    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public SquirrelAlarmNode() {
        // Pre-fill the common case so the node works out of the box after wiring a trigger.
        host.setValue(DEFAULT_HOST);
    }

    @Override
    public void process() {
        String endpoint = normalizeStatus(status.getValue());
        if (endpoint == null) {
            return; // nothing selected/wired yet - don't poke the device
        }
        String target = "http://" + resolveHost(host.getValue()) + "/" + endpoint;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | IllegalArgumentException e) {
            // Device offline, name didn't resolve, or a malformed host - the alarm is
            // best-effort, so swallow it rather than aborting the rest of the flow.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Strips a pasted scheme/path so the Host field tolerates e.g. "http://squirrel-alarm.local/". */
    private static String resolveHost(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_HOST;
        }
        String trimmed = value.trim().replaceFirst("^https?://", "");
        int slash = trimmed.indexOf('/');
        if (slash >= 0) {
            trimmed = trimmed.substring(0, slash);
        }
        return trimmed.isBlank() ? DEFAULT_HOST : trimmed;
    }

    /** Maps a Status value to the device's endpoint path (bird/squirrel/clear), or null if empty. */
    private static String normalizeStatus(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.isBlank() ? null : trimmed;
    }

    @Override
    public void configureInputs() {
        addInput(host);
        addInput(status);
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }
}
