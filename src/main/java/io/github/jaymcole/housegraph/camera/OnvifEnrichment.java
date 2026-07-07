package io.github.jaymcole.housegraph.camera;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enriches a {@link DiscoveredCamera} with its <em>authenticated</em> ONVIF details — a port
 * of the AnimalNotifier {@code enrich_onvif}. WS-Discovery finds a camera and its raw scopes,
 * but the good values need a login: the clean model comes from {@code GetDeviceInformation},
 * and the app-set custom name is an {@code odm:name:<value>} scope only returned to an
 * authenticated {@code GetScopes}. Auth is a WS-Security {@code UsernameToken} digest —
 * {@code Base64(SHA1(nonce + created + password))} — so the password never crosses the wire in
 * the clear.
 * <p>
 * Everything here is best-effort over plain HTTP to the ONVIF device service on port 8000: if
 * ONVIF is disabled or the camera is unreachable, the calls return empty and the camera keeps
 * whatever it had (so a port-scan-only camera never reports a bogus model). Without a password
 * the model may still be read from the (unauthenticated) hardware scope, but the custom name
 * can't be.
 */
public final class OnvifEnrichment {

    private static final int ONVIF_PORT = 8000;
    private static final String TDS = "http://www.onvif.org/ver10/device/wsdl";
    private static final String WSS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss";

    private static final DateTimeFormatter CREATED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Pattern SCOPE_ITEM = Pattern.compile(
            "<[^>]*\\bScopeItem\\b[^>]*>(.*?)</[^>]*\\bScopeItem\\b[^>]*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final String ODM_NAME_PREFIX = "odm:name:";

    private static final HttpClient CLIENT = HttpClient.newBuilder().build();

    private OnvifEnrichment() {
    }

    /**
     * Returns {@code camera} enriched with its ONVIF model and (with a password) app-set custom
     * name. A camera whose ONVIF service can't be reached is returned unchanged.
     *
     * @param user           login user (typically {@code admin})
     * @param password       login password; without it only the unauthenticated model is read
     * @param timeoutSeconds per-request timeout
     */
    public static DiscoveredCamera enrich(DiscoveredCamera camera, String user, String password, int timeoutSeconds) {
        if (camera.ip() == null) {
            return camera;
        }
        String header = password == null || password.isBlank() ? "" : securityHeader(user, password);

        String info = onvifCall(camera.ip(), "<GetDeviceInformation xmlns=\"" + TDS + "\"/>", timeoutSeconds, header);
        String model = xmlField(info, "Model");

        String scopesXml = onvifCall(camera.ip(), "<GetScopes xmlns=\"" + TDS + "\"/>", timeoutSeconds, header);
        List<String> items = scopeItems(scopesXml);
        String customName = customName(items);

        // Clean GetDeviceInformation model, else the model in the *real* ONVIF hardware scope.
        // Never a port-scan placeholder (only present when items is empty), so an un-enriched
        // camera reports no model rather than a bogus one.
        String enrichedModel = model != null ? model : (!items.isEmpty() ? camera.hardware() : null);
        return camera.enriched(customName, enrichedModel, items);
    }

    // --- WS-Security auth ---------------------------------------------------------

    /** A WS-Security {@code UsernameToken} digest SOAP header (see class doc). */
    static String securityHeader(String user, String password) {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String created = CREATED.format(Instant.now());
        String digest = Base64.getEncoder().encodeToString(
                sha1(concat(nonce, (created + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8))));
        return "<s:Header><Security s:mustUnderstand=\"1\" xmlns=\"" + WSS + "-wssecurity-secext-1.0.xsd\">"
                + "<UsernameToken><Username>" + (user == null ? "" : user) + "</Username>"
                + "<Password Type=\"" + WSS + "-username-token-profile-1.0#PasswordDigest\">" + digest + "</Password>"
                + "<Nonce EncodingType=\"" + WSS + "-soap-message-security-1.0#Base64Binary\">"
                + Base64.getEncoder().encodeToString(nonce) + "</Nonce>"
                + "<Created xmlns=\"" + WSS + "-wssecurity-utility-1.0.xsd\">" + created + "</Created>"
                + "</UsernameToken></Security></s:Header>";
    }

    // --- SOAP call ----------------------------------------------------------------

    /** POSTs a SOAP request to the camera's ONVIF device service, returning the body or "" on any failure. */
    private static String onvifCall(String ip, String body, int timeoutSeconds, String header) {
        String endpoint = "http://" + ip + ":" + ONVIF_PORT + "/onvif/device_service";
        String envelope = "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">"
                + header + "<s:Body>" + body + "</s:Body></s:Envelope>";
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(java.time.Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Content-Type", "application/soap+xml")
                .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8))
                .build();
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException e) {
            return ""; // ONVIF disabled, unreachable, or any network/parse error → no enrichment
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    // --- Response parsing (package-visible for testing) ---------------------------

    /** The trimmed text content of the first {@code <tag>...</tag>}, or null if absent/empty. */
    static String xmlField(String text, String tag) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile("<[^>]*\\b" + tag + "\\b[^>]*>(.*?)</[^>]*\\b" + tag + "\\b[^>]*>",
                Pattern.DOTALL).matcher(text);
        if (m.find()) {
            String value = m.group(1).trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    /** The non-empty {@code <ScopeItem>} values from a {@code GetScopes} response. */
    static List<String> scopeItems(String scopesXml) {
        List<String> items = new ArrayList<>();
        if (scopesXml == null) {
            return items;
        }
        Matcher m = SCOPE_ITEM.matcher(scopesXml);
        while (m.find()) {
            String item = m.group(1).trim();
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    /** The app-set custom name from an {@code odm:name:<value>} scope item (URL-decoded), or null. */
    static String customName(List<String> items) {
        for (String item : items) {
            if (item.toLowerCase().startsWith(ODM_NAME_PREFIX)) {
                String decoded = URLDecoder.decode(item.substring(ODM_NAME_PREFIX.length()), StandardCharsets.UTF_8).trim();
                return decoded.isEmpty() ? null : decoded;
            }
        }
        return null;
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e); // guaranteed present on every JRE
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
