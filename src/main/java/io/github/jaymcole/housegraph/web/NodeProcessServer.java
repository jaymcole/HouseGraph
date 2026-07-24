package io.github.jaymcole.housegraph.web;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Launches and supervises an external <b>Node.js</b> server as a child process — the long-lived
 * resource behind a Node-server node, the sibling of {@link LocalWebServer}. Where
 * {@code LocalWebServer} serves static files from the JVM itself, this class hands hosting to a
 * user-supplied Node program (an Express app, a Vite dev server, whatever {@code npm start}
 * runs) and only owns its <em>lifecycle</em>: spawn it in the chosen project directory, stream
 * its console output into HouseGraph's log, and kill it (and its child tree) on stop.
 * <p>
 * It <em>does not</em> bind the HTTP port — the Node process does. HouseGraph still advertises
 * {@code <name>.local} over jmdns multicast DNS pointing at the declared port, so the site is
 * reachable at {@code http://<name>.local:<port>/} from any mDNS-aware device — but the Node app
 * is responsible for actually listening on that port (typically via {@code process.env.PORT},
 * which this class sets). If they disagree, the advertisement points nowhere.
 * <p>
 * Like {@code LocalWebServer}, this class keeps no UI concerns: {@link #start} spawns the process
 * and joins the multicast group (call it off the UI thread — process launch and mDNS setup touch
 * the OS and network), {@link #stop} tears both halves down and is idempotent. Instances are
 * single-use per run but reusable after {@link #stop}.
 */
public final class NodeProcessServer {

    private static final Logger log = Log.get(NodeProcessServer.class);

    /** {@code true} on Windows, where the launcher shell is {@code cmd /c} rather than {@code sh -c}. */
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final Object lock = new Object();
    private Process process;
    private Thread outputPump;
    private JmDNS jmdns;
    private volatile String url;

    /**
     * Spawns {@code command} as a Node.js server rooted at {@code workingDir} and advertises it as
     * {@code name.local:port} via mDNS. The command runs through the platform shell so PATH-resolved
     * launchers work as typed ({@code npm start}, {@code node server.js}, {@code npx vite}); its
     * combined stdout/stderr is pumped into the log. {@code PORT} is exported into the child's
     * environment so a well-behaved Node app binds the advertised port.
     * <p>
     * Blocks only briefly (process spawn + mDNS join); call from a background thread.
     *
     * @param workingDir the Node project directory to run in (must be an existing directory)
     * @param command    the shell command that starts the server (e.g. {@code npm start}); must be non-blank
     * @param name       the mDNS host/service name; the site is advertised at {@code http://name.local:port/}
     * @param port       the TCP port the Node app is expected to listen on (advertised + exported as {@code PORT})
     * @throws IOException              if the process can't be spawned or mDNS can't start
     * @throws IllegalArgumentException if {@code workingDir} is not a directory, or {@code command}/{@code name} is blank
     */
    public void start(Path workingDir, String command, String name, int port) throws IOException {
        if (workingDir == null || !Files.isDirectory(workingDir)) {
            throw new IllegalArgumentException("Node project directory does not exist: " + workingDir);
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Start command must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Server name must not be blank");
        }
        Path base = workingDir.toAbsolutePath().normalize();

        synchronized (lock) {
            spawnLocked(base, command, port);

            // Advertise <name>.local (A record) and an _http._tcp service on the same name, exactly
            // like LocalWebServer — even though it's the Node process, not us, that binds the port.
            try {
                InetAddress advertiseAddr = LanAddress.siteLocal();
                JmDNS dns = JmDNS.create(advertiseAddr, name);
                ServiceInfo info = ServiceInfo.create("_http._tcp.local.", name, port, "path=/");
                dns.registerService(info);
                this.jmdns = dns;
                this.url = "http://" + name + ".local:" + port + "/";
                log.info("Node server '{}' running `{}` in {}, advertised at {}", name, command, base, url);
            } catch (IOException e) {
                // mDNS failed, but the process is up — kill it so start() is all-or-nothing.
                stopProcessLocked();
                throw e;
            }
        }
    }

    /**
     * Spawns the child process on the platform shell, redirecting stderr into stdout and pumping the
     * merged stream into the log on a daemon thread. Caller holds {@link #lock}.
     */
    private void spawnLocked(Path base, String command, int port) throws IOException {
        if (process != null) {
            throw new IllegalStateException("Node server already running");
        }
        ProcessBuilder builder = new ProcessBuilder(shellCommand(command))
                .directory(base.toFile())
                .redirectErrorStream(true);
        builder.environment().put("PORT", Integer.toString(port));

        Process started = builder.start();
        Thread pump = new Thread(() -> pumpOutput(started), "node-server-output");
        pump.setDaemon(true);
        pump.start();

        this.process = started;
        this.outputPump = pump;
    }

    /** Wraps a user command in the platform shell so PATH-resolved launchers (npm, npx) work as typed. */
    private static List<String> shellCommand(String command) {
        return IS_WINDOWS
                ? List.of("cmd.exe", "/c", command)
                : List.of("sh", "-c", command);
    }

    /** Streams the child's merged stdout/stderr into the log until the stream closes (process exit). */
    private void pumpOutput(Process proc) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[node] {}", line);
            }
        } catch (IOException e) {
            log.warn("Node server output stream closed: {}", e.getMessage());
        }
    }

    /** Idempotent teardown of both the mDNS advertisement and the child process (whole tree). */
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
            stopProcessLocked();
            url = null;
        }
    }

    private void stopProcessLocked() {
        if (process != null) {
            // Kill the whole descendant tree first: `npm start` forks node, and destroying only the
            // shell would orphan the server still holding the port.
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            process = null;
        }
        if (outputPump != null) {
            outputPump.interrupt();
            outputPump = null;
        }
    }

    public boolean isRunning() {
        synchronized (lock) {
            return process != null && process.isAlive();
        }
    }

    /** The advertised {@code http://<name>.local:<port>/} URL while running, else {@code null}. */
    public String url() {
        return url;
    }
}
