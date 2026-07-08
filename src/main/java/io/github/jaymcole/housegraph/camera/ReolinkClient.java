package io.github.jaymcole.housegraph.camera;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * A minimal client for Reolink's HTTP CGI API ({@code /cgi-bin/api.cgi}), just enough to
 * read a camera's current detection state. Reolink batches commands as a JSON array POST;
 * we log in for a short-lived token, ask for the AI and plain-motion state in one request,
 * then log out again so we don't leak the camera's limited session pool when polled in a
 * loop.
 * <p>
 * AI models split a detection into categories ({@code people}, {@code vehicle},
 * {@code dog_cat}); older/AI-less cameras only report plain {@code GetMdState}. We fold
 * both into a single {@link DetectionState}, treating a category the camera doesn't
 * support as simply "not detected". Everything is best-effort over plain HTTP on port 80;
 * a camera reachable only over HTTPS should be given a {@code https://…} host.
 */
public final class ReolinkClient {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private ReolinkClient() {
    }

    /**
     * The detection state read from a camera in one poll. Each flag is whether that kind of
     * thing is being detected <em>right now</em>; {@link #animal} folds Reolink's
     * {@code dog_cat} category, and {@link #motion} is the plain (non-AI) motion signal.
     *
     * @param human   whether a person is being detected right now
     * @param vehicle whether a vehicle is being detected right now
     * @param animal  whether an animal (Reolink's {@code dog_cat}) is being detected right now
     * @param motion  whether plain (non-AI) motion is being detected right now
     */
    public record DetectionState(boolean human, boolean vehicle, boolean animal, boolean motion) {

        /**
         * Whether anything at all is currently detected.
         *
         * @return true if any of the detection flags is set
         */
        public boolean anyDetected() {
            return human || vehicle || animal || motion;
        }

        /**
         * The single most specific thing detected, as a lowercase label:
         * {@code human > vehicle > animal > motion > none}. This is the "status group" —
         * one of a fixed set — suitable for driving downstream branching or a status sign.
         */
        public String topStatus() {
            if (human) {
                return "human";
            }
            if (vehicle) {
                return "vehicle";
            }
            if (animal) {
                return "animal";
            }
            if (motion) {
                return "motion";
            }
            return "none";
        }
    }

    /**
     * Polls one camera for its current detection state.
     *
     * @param host           IP or hostname; an explicit {@code http://}/{@code https://}
     *                       scheme and/or {@code :port} are honoured, otherwise plain HTTP
     *                       on port 80 is assumed
     * @param user           login user (typically {@code admin})
     * @param password       login password (may be empty for an unset camera)
     * @param channel        camera channel (0 for a standalone camera; NVR channels vary)
     * @param timeoutSeconds per-request timeout
     * @throws ReolinkException if the camera can't be reached, login is rejected, or the
     *                          response can't be understood — callers that poll in a loop
     *                          can let this surface as the node's error for that pass
     */
    public static DetectionState poll(String host, String user, String password, int channel, int timeoutSeconds) {
        String base = baseUrl(host);
        int timeout = Math.max(1, timeoutSeconds);
        String token = login(base, user, password, timeout);
        try {
            return queryState(base, token, channel, timeout);
        } finally {
            logout(base, token, timeout);
        }
    }

    /**
     * Grabs a single still frame from one camera as JPEG bytes.
     * <p>
     * Reolink's {@code Snap} command is a plain authenticated GET that streams back a JPEG
     * (unlike the JSON-batch commands used elsewhere in this client). We log in for a
     * short-lived token, fetch the frame, then log out — same session hygiene as {@link #poll}
     * so a node grabbing frames in a loop doesn't exhaust the camera's session pool. Bytes are
     * returned raw (this package stays JavaFX-free); the caller wraps them into an image.
     *
     * @param host           IP or hostname; scheme/port honoured, else plain HTTP on port 80
     * @param user           login user (typically {@code admin})
     * @param password       login password (may be empty for an unset camera)
     * @param channel        camera channel (0 for a standalone camera; NVR channels vary)
     * @param timeoutSeconds per-request timeout
     * @return the JPEG-encoded frame
     * @throws ReolinkException if the camera can't be reached, login is rejected, or the
     *                          camera returns an error instead of an image
     */
    public static byte[] snapshot(String host, String user, String password, int channel, int timeoutSeconds) {
        String base = baseUrl(host);
        int timeout = Math.max(1, timeoutSeconds);
        String token = login(base, user, password, timeout);
        try {
            return fetchSnapshot(base, token, channel, timeout);
        } finally {
            logout(base, token, timeout);
        }
    }

    private static byte[] fetchSnapshot(String base, String token, int channel, int timeout) {
        // rs is a cache-buster the camera expects; any changing token does.
        String rs = Long.toHexString(System.nanoTime());
        String url = base + "?cmd=Snap&channel=" + channel + "&rs=" + rs + "&token=" + token;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new ReolinkException("Could not reach camera at " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReolinkException("Interrupted while contacting camera at " + url, e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new ReolinkException("Camera returned HTTP " + response.statusCode() + " for " + url);
        }
        byte[] body = response.body();
        if (!looksLikeJpeg(body)) {
            // On error (bad token, unsupported channel) Reolink answers with a JSON blob, not an image.
            String detail = body == null ? "" : ": " + new String(body, StandardCharsets.UTF_8).strip();
            throw new ReolinkException("Camera did not return a JPEG frame" + detail);
        }
        return body;
    }

    /** JPEG magic number: FF D8 FF. Guards against Reolink's JSON error body being taken for a frame. */
    private static boolean looksLikeJpeg(byte[] data) {
        return data != null && data.length >= 3
                && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF;
    }

    private static String login(String base, String user, String password, int timeout) {
        JSONObject credentials = new JSONObject()
                .put("userName", user == null ? "" : user)
                .put("password", password == null ? "" : password);
        JSONObject command = new JSONObject()
                .put("cmd", "Login")
                .put("param", new JSONObject().put("User", credentials));
        JSONArray response = send(base + "?cmd=Login", new JSONArray().put(command), timeout);

        JSONObject result = firstResult(response, "Login");
        JSONObject token = result.optJSONObject("value") == null
                ? null
                : result.getJSONObject("value").optJSONObject("Token");
        String name = token == null ? null : token.optString("name", null);
        if (name == null || name.isBlank()) {
            throw new ReolinkException("Login to " + base + " was rejected (check user/password).");
        }
        return name;
    }

    private static DetectionState queryState(String base, String token, int channel, int timeout) {
        JSONObject param = new JSONObject().put("channel", channel);
        JSONArray body = new JSONArray()
                .put(new JSONObject().put("cmd", "GetAiState").put("action", 0).put("param", param))
                .put(new JSONObject().put("cmd", "GetMdState").put("action", 0).put("param", param));
        JSONArray response = send(base + "?token=" + token, body, timeout);

        JSONObject ai = resultFor(response, "GetAiState");
        JSONObject md = resultFor(response, "GetMdState");
        boolean human = alarmActive(ai, "people") || alarmActive(ai, "face");
        boolean vehicle = alarmActive(ai, "vehicle");
        boolean animal = alarmActive(ai, "dog_cat");
        boolean motion = md != null && md.optJSONObject("value") != null
                && md.getJSONObject("value").optInt("state", 0) != 0;
        return new DetectionState(human, vehicle, animal, motion);
    }

    private static void logout(String base, String token, int timeout) {
        try {
            JSONArray body = new JSONArray()
                    .put(new JSONObject().put("cmd", "Logout").put("param", new JSONObject()));
            send(base + "?cmd=Logout&token=" + token, body, timeout);
        } catch (ReolinkException e) {
            // Best-effort - the token leases out on its own; don't fail a good poll over it.
        }
    }

    /** True if the AI category exists, is supported, and its alarm is currently firing. */
    private static boolean alarmActive(JSONObject aiResult, String category) {
        if (aiResult == null) {
            return false;
        }
        JSONObject value = aiResult.optJSONObject("value");
        JSONObject item = value == null ? null : value.optJSONObject(category);
        return item != null && item.optInt("support", 0) != 0 && item.optInt("alarm_state", 0) != 0;
    }

    /** The result object for {@code cmd} in a batch response, or null if absent/errored. */
    private static JSONObject resultFor(JSONArray response, String cmd) {
        for (int i = 0; i < response.length(); i++) {
            JSONObject entry = response.optJSONObject(i);
            if (entry != null && cmd.equals(entry.optString("cmd")) && entry.optInt("code", -1) == 0) {
                return entry;
            }
        }
        return null;
    }

    /** The first result object, asserting its command succeeded (used for Login where a failure is fatal). */
    private static JSONObject firstResult(JSONArray response, String cmd) {
        JSONObject entry = response.optJSONObject(0);
        if (entry == null) {
            throw new ReolinkException("Empty response to " + cmd + ".");
        }
        if (entry.optInt("code", -1) != 0) {
            JSONObject error = entry.optJSONObject("error");
            String detail = error == null ? "" : " (" + error.optString("detail", "") + ")";
            throw new ReolinkException(cmd + " failed" + detail + ".");
        }
        return entry;
    }

    private static JSONArray send(String url, JSONArray body, int timeout) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ReolinkException("Could not reach camera at " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReolinkException("Interrupted while contacting camera at " + url, e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new ReolinkException("Camera returned HTTP " + response.statusCode() + " for " + url);
        }
        try {
            return new JSONArray(response.body());
        } catch (RuntimeException e) {
            throw new ReolinkException("Unexpected (non-JSON-array) response from camera at " + url, e);
        }
    }

    /** Normalises a host into a scheme-qualified {@code /cgi-bin/api.cgi} base URL. */
    private static String baseUrl(String host) {
        String trimmed = host == null ? "" : host.trim();
        if (trimmed.isEmpty()) {
            throw new ReolinkException("No camera host specified.");
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            trimmed = "http://" + trimmed;
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/cgi-bin/api.cgi";
    }

    /** Raised for any failure talking to a Reolink camera; message is safe to show in the UI. */
    public static final class ReolinkException extends RuntimeException {
        public ReolinkException(String message) {
            super(message);
        }

        public ReolinkException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
