package io.github.jaymcole.housegraph.web;

import io.github.jaymcole.housegraph.store.JsonDocumentStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the static-file serving half of {@link LocalWebServer} through real HTTP
 * requests (the mDNS half needs multicast and is environment-dependent, so it's driven
 * via the {@code startHttpForTest} seam without advertising).
 */
class LocalWebServerTest {

    private final LocalWebServer server = new LocalWebServer();
    private int port;

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private void serve(Path root) throws IOException {
        port = server.startHttpForTest(root, 0, null);
    }

    private void serve(Path root, DocumentApi api) throws IOException {
        port = server.startHttpForTest(root, 0, api);
    }

    @Test
    void servesIndexHtmlForRootWithHtmlContentType(@TempDir Path site) throws IOException {
        Files.writeString(site.resolve("index.html"), "<h1>hello</h1>");
        serve(site);

        HttpURLConnection conn = get("/");
        assertEquals(200, conn.getResponseCode());
        assertTrue(conn.getContentType().startsWith("text/html"), conn.getContentType());
        assertEquals("<h1>hello</h1>", body(conn));
    }

    @Test
    void servesNestedAssetWithTypeFromExtension(@TempDir Path site) throws IOException {
        Files.writeString(site.resolve("style.css"), "body{}");
        serve(site);

        HttpURLConnection conn = get("/style.css");
        assertEquals(200, conn.getResponseCode());
        assertTrue(conn.getContentType().startsWith("text/css"), conn.getContentType());
        assertEquals("body{}", body(conn));
    }

    @Test
    void missingFileIs404(@TempDir Path site) throws IOException {
        Files.writeString(site.resolve("index.html"), "hi");
        serve(site);

        HttpURLConnection conn = get("/nope.html");
        assertEquals(404, conn.getResponseCode());
    }

    @Test
    void pathTraversalIsRejected(@TempDir Path site) throws IOException {
        // A secret file lives outside the served directory; a "../" request must not reach it.
        Path secret = site.getParent().resolve("secret-" + System.nanoTime() + ".txt");
        Files.writeString(secret, "top secret");
        Files.writeString(site.resolve("index.html"), "hi");
        serve(site);

        // Send the raw, un-normalized path — HttpURLConnection would collapse "../" client-side.
        String status = rawRequestStatusLine("/../" + secret.getFileName());
        assertTrue(status.contains("403"), "expected 403 for traversal, got: " + status);

        Files.deleteIfExists(secret);
    }

    @Test
    void apiGetReturnsEmptyDocumentAsJson(@TempDir Path site, @TempDir Path data) throws IOException {
        Files.writeString(site.resolve("index.html"), "hi");
        serve(site, storeApi(data.resolve("document.json")));

        HttpURLConnection conn = get("/api/data");
        assertEquals(200, conn.getResponseCode());
        assertTrue(conn.getContentType().startsWith("application/json"), conn.getContentType());
        assertEquals("{}", body(conn));
    }

    @Test
    void apiPutStoresDocumentThenGetReturnsIt(@TempDir Path site, @TempDir Path data) throws IOException {
        Files.writeString(site.resolve("index.html"), "hi");
        serve(site, storeApi(data.resolve("document.json")));

        HttpURLConnection put = send("PUT", "/api/data", "{\"note\":\"hello\"}");
        assertEquals(204, put.getResponseCode());

        assertEquals("{\"note\":\"hello\"}", body(get("/api/data")));
    }

    @Test
    void apiPutRejectsInvalidJsonWith400(@TempDir Path site, @TempDir Path data) throws IOException {
        Files.writeString(site.resolve("index.html"), "hi");
        serve(site, storeApi(data.resolve("document.json")));

        assertEquals(400, send("PUT", "/api/data", "not json").getResponseCode());
    }

    @Test
    void apiUnsupportedMethodIs405(@TempDir Path site, @TempDir Path data) throws IOException {
        Files.writeString(site.resolve("index.html"), "hi");
        serve(site, storeApi(data.resolve("document.json")));

        assertEquals(405, send("DELETE", "/api/data", null).getResponseCode());
    }

    @Test
    void apiReturns503WhenStoreUnavailable(@TempDir Path site) throws IOException {
        Files.writeString(site.resolve("index.html"), "hi");
        serve(site, new DocumentApi() {
            @Override
            public String read() {
                throw new IllegalStateException("no store");
            }

            @Override
            public void write(String json) {
                throw new IllegalStateException("no store");
            }
        });

        assertEquals(503, get("/api/data").getResponseCode());
    }

    @Test
    void staticFilesStillServeAlongsideApi(@TempDir Path site, @TempDir Path data) throws IOException {
        Files.writeString(site.resolve("index.html"), "<h1>hi</h1>");
        serve(site, storeApi(data.resolve("document.json")));

        assertEquals("<h1>hi</h1>", body(get("/")));
    }

    /** A real store-backed API, so these double as integration coverage of {@link JsonDocumentStore}. */
    private static DocumentApi storeApi(Path file) {
        JsonDocumentStore store = new JsonDocumentStore(file);
        return new DocumentApi() {
            @Override
            public String read() {
                return store.get();
            }

            @Override
            public void write(String json) {
                store.set(json);
            }
        };
    }

    private HttpURLConnection get(String path) throws IOException {
        URI uri = URI.create("http://localhost:" + port + path);
        return (HttpURLConnection) uri.toURL().openConnection();
    }

    private HttpURLConnection send(String method, String path, String requestBody) throws IOException {
        URI uri = URI.create("http://localhost:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod(method);
        if (requestBody != null) {
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }
        }
        return conn;
    }

    private static String body(HttpURLConnection conn) throws IOException {
        try (InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Sends a raw HTTP request so the path isn't normalized, and returns the response status line. */
    private String rawRequestStatusLine(String rawPath) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port));
            socket.getOutputStream().write(
                    ("GET " + rawPath + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return reader.readLine();
        }
    }
}
