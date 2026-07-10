package io.github.jaymcole.housegraph.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small static-file web server that publishes itself on the LAN as {@code <name>.local}
 * — the long-lived resource behind a web-server node. It pairs two JDK-external-free
 * pieces:
 * <ul>
 *   <li>the JDK's built-in {@link HttpServer} (no dependency) serving a directory of
 *       static files, with directory-index and path-traversal handling;</li>
 *   <li><a href="https://github.com/jmdns/jmdns">jmdns</a> multicast DNS, advertising a
 *       {@code <name>.local} A record plus an {@code _http._tcp} service so the site is
 *       reachable at {@code http://<name>.local:<port>/} from any mDNS-aware device on the
 *       network (macOS always, Windows 10+, Linux with Avahi).</li>
 * </ul>
 * Like {@code DiscordBot}, this class only manages start/stop and keeps no UI concerns:
 * {@link #start} binds the socket and joins the multicast group (call it off the UI
 * thread — the mDNS setup touches the network), {@link #stop} tears both halves down and
 * is idempotent. Instances are single-use per run but reusable after {@link #stop}.
 */
public final class LocalWebServer {

    private static final Logger log = Log.get(LocalWebServer.class);

    private final Object lock = new Object();
    private HttpServer httpServer;
    private ExecutorService httpExecutor;
    private JmDNS jmdns;
    private volatile String url;

    /**
     * Serves {@code root} over HTTP on {@code port} and advertises it as {@code name.local}
     * via mDNS. Blocks only briefly (socket bind + mDNS join); call from a background thread.
     *
     * @param root the directory of static files to serve (must be an existing directory)
     * @param name the mDNS host/service name; the site becomes reachable at {@code http://name.local:port/}
     * @param port the TCP port to listen on
     * @throws IOException              if the port can't be bound or mDNS can't start
     * @throws IllegalArgumentException if {@code root} is not an existing directory or {@code name} is blank
     */
    public void start(Path root, String name, int port) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("Website directory does not exist: " + root);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Website name must not be blank");
        }
        Path base = root.toAbsolutePath().normalize();

        synchronized (lock) {
            bindHttpLocked(base, port);

            // Advertise <name>.local (A record) and an _http._tcp service on the same name.
            // JmDNS bound with the host name answers A queries for "<name>.local".
            try {
                InetAddress advertiseAddr = siteLocalAddress();
                JmDNS dns = JmDNS.create(advertiseAddr, name);
                ServiceInfo info = ServiceInfo.create("_http._tcp.local.", name, port, "path=/");
                dns.registerService(info);
                this.jmdns = dns;
                this.url = "http://" + name + ".local:" + port + "/";
                log.info("Web server '{}' serving {} at {}", name, base, url);
            } catch (IOException e) {
                // mDNS failed, but the HTTP server is up — unwind it so start() is all-or-nothing.
                stopHttpLocked();
                throw e;
            }
        }
    }

    /**
     * Package-visible seam for tests: starts only the static-file HTTP server (no mDNS,
     * which needs multicast and is environment-dependent) on an ephemeral port, and
     * returns the actual bound port.
     */
    int startHttpForTest(Path root, int port) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("Website directory does not exist: " + root);
        }
        Path base = root.toAbsolutePath().normalize();
        synchronized (lock) {
            bindHttpLocked(base, port);
            return httpServer.getAddress().getPort();
        }
    }

    /**
     * Binds and starts the HTTP server on the wildcard address (so both localhost and the
     * LAN can reach it), serving {@code base}. Caller holds {@link #lock}.
     */
    private void bindHttpLocked(Path base, int port) throws IOException {
        if (httpServer != null) {
            throw new IllegalStateException("Server already running");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new StaticFileHandler(base));
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        this.httpServer = server;
        this.httpExecutor = executor;
    }

    /** Idempotent teardown of both the mDNS advertisement and the HTTP server. */
    public void stop() {
        synchronized (lock) {
            if (jmdns != null) {
                try {
                    jmdns.unregisterAllServices();
                    jmdns.close();
                } catch (IOException e) {
                    log.warn("Error closing mDNS: {}", e.getMessage());
                }
                jmdns = null;
            }
            stopHttpLocked();
            url = null;
        }
    }

    public boolean isRunning() {
        synchronized (lock) {
            return httpServer != null;
        }
    }

    /** The advertised {@code http://<name>.local:<port>/} URL while running, else {@code null}. */
    public String url() {
        return url;
    }

    private void stopHttpLocked() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
    }

    /**
     * Picks the local IPv4 address to advertise over mDNS (jmdns needs a concrete interface
     * address, not the wildcard). This must be the address the LAN can actually reach —
     * getting it wrong means {@code <name>.local} resolves to an unroutable address even
     * though the site is up.
     * <p>
     * The reliable way is to ask the OS which source address it would use to reach the
     * network: a UDP socket "connected" to a remote address does a route lookup (without
     * sending anything) and binds to the chosen source. That picks the real Wi-Fi/Ethernet
     * interface and sidesteps virtual adapters (WSL, Hyper-V) and link-local
     * ({@code 169.254.x}) addresses that {@link NetworkInterface} enumeration can't reliably
     * tell apart ({@code isVirtual()} only flags sub-interfaces, not virtual NICs). Falls
     * back to interface enumeration, then the default local host, if there's no route.
     */
    private static InetAddress siteLocalAddress() throws IOException {
        try (DatagramSocket probe = new DatagramSocket()) {
            // Any routable address works as the probe target; nothing is actually sent.
            probe.connect(InetAddress.getByName("8.8.8.8"), 53);
            InetAddress local = probe.getLocalAddress();
            if (local instanceof Inet4Address && !local.isAnyLocalAddress()
                    && !local.isLoopbackAddress() && !local.isLinkLocalAddress()) {
                return local;
            }
        } catch (IOException e) {
            log.warn("Could not determine preferred local address, falling back: {}", e.getMessage());
        }
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        return addr;
                    }
                }
            }
        } catch (SocketException e) {
            log.warn("Could not enumerate network interfaces: {}", e.getMessage());
        }
        return InetAddress.getLocalHost();
    }

    /**
     * Serves files from a fixed base directory. Rejects path traversal (the resolved file
     * must stay inside the base), serves {@code index.html} for a directory request, and
     * sets a best-effort Content-Type from the file extension.
     */
    private static final class StaticFileHandler implements HttpHandler {

        private final Path base;

        StaticFileHandler(Path base) {
            this.base = base;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String rawPath = exchange.getRequestURI().getPath();
                Path target = resolve(rawPath);
                if (target == null) {
                    respond(exchange, 403, "text/plain", "Forbidden".getBytes());
                    return;
                }
                if (Files.isDirectory(target)) {
                    target = target.resolve("index.html");
                }
                if (!Files.isRegularFile(target)) {
                    respond(exchange, 404, "text/plain", "Not Found".getBytes());
                    return;
                }
                byte[] body = Files.readAllBytes(target);
                respond(exchange, 200, contentType(target), body);
            }
        }

        /** Resolves a request path under {@code base}, or {@code null} if it escapes the base. */
        private Path resolve(String rawPath) {
            String relative = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
            Path resolved = base.resolve(relative).normalize();
            return resolved.startsWith(base) ? resolved : null;
        }

        private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }

        private static String contentType(Path file) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            int dot = name.lastIndexOf('.');
            String ext = dot < 0 ? "" : name.substring(dot + 1);
            String type = CONTENT_TYPES.get(ext);
            return type != null ? type : "application/octet-stream";
        }

        private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
                Map.entry("html", "text/html; charset=utf-8"),
                Map.entry("htm", "text/html; charset=utf-8"),
                Map.entry("css", "text/css; charset=utf-8"),
                Map.entry("js", "text/javascript; charset=utf-8"),
                Map.entry("mjs", "text/javascript; charset=utf-8"),
                Map.entry("json", "application/json; charset=utf-8"),
                Map.entry("svg", "image/svg+xml"),
                Map.entry("png", "image/png"),
                Map.entry("jpg", "image/jpeg"),
                Map.entry("jpeg", "image/jpeg"),
                Map.entry("gif", "image/gif"),
                Map.entry("webp", "image/webp"),
                Map.entry("ico", "image/x-icon"),
                Map.entry("txt", "text/plain; charset=utf-8"),
                Map.entry("woff", "font/woff"),
                Map.entry("woff2", "font/woff2"));
    }
}
