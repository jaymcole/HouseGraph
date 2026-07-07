package io.github.jaymcole.housegraph.camera;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers IP cameras (Reolink and any other ONVIF device) on the local network — a
 * Java port of the AnimalNotifier discovery tool.
 * <p>
 * Primary method is <b>ONVIF WS-Discovery</b>: a SOAP "Probe" multicast to
 * {@code 239.255.255.250:3702} over UDP, sent out every local interface; each device
 * replies with its service address(es) and scopes (name/hardware/manufacturer). If
 * nothing answers (Reolink ships with ONVIF off) we fall back to a concurrent <b>TCP
 * port-scan</b> of each local /24 for the RTSP port (554). Either way we resolve each
 * IP to a <b>MAC</b> from the OS ARP cache — the stable id used to key the config.
 * <p>
 * Pure JDK, no dependency; multicast only reaches the local subnet, so this must run on
 * the same network/VLAN as the cameras.
 */
public final class CameraDiscovery {

    private static final String WS_DISCOVERY_ADDR = "239.255.255.250";
    private static final int WS_DISCOVERY_PORT = 3702;
    /** Multicast TTL for the probe: 2 hops, so it reaches the local subnet reliably (matches the reference tool). */
    private static final int MULTICAST_TTL = 2;
    private static final int[] CAMERA_PORTS = {554, 8000, 9000};
    private static final int RTSP_PORT = 554;

    private static final Pattern XADDRS = fieldPattern("XAddrs");
    private static final Pattern SCOPES = fieldPattern("Scopes");
    private static final Pattern HOST_IN_URL = Pattern.compile("://([^/:]+)");
    private static final Pattern MAC = Pattern.compile("([0-9a-fA-F]{2}(?:[:-][0-9a-fA-F]{2}){5})");
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3})");

    private static final String PROBE =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\""
                    + " xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\""
                    + " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\""
                    + " xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">"
                    + "<e:Header>"
                    + "<w:MessageID>uuid:%s</w:MessageID>"
                    + "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>"
                    + "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>"
                    + "</e:Header>"
                    + "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body>"
                    + "</e:Envelope>";

    private CameraDiscovery() {
    }

    /**
     * Runs a full discovery sweep: ONVIF probe, port-scan fallback if nothing answers,
     * then MAC resolution. Cameras are keyed/deduped by IP and sorted by IP.
     *
     * @param timeoutSeconds how long to listen for ONVIF replies
     */
    public static List<DiscoveredCamera> discover(int timeoutSeconds) {
        Map<String, List<String>> scopesByIp = probeOnvif(timeoutSeconds);
        if (scopesByIp.isEmpty()) {
            scopesByIp = portScan();
        }

        Map<String, String> arp = parseArpTable(runArp());
        List<DiscoveredCamera> cameras = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : scopesByIp.entrySet()) {
            String ip = entry.getKey();
            cameras.add(new DiscoveredCamera(ip, arp.get(ip), entry.getValue()));
        }
        cameras.sort((a, b) -> compareIps(a.ip(), b.ip()));
        return cameras;
    }

    // --- ONVIF WS-Discovery -------------------------------------------------------

    private static Map<String, List<String>> probeOnvif(int timeoutSeconds) {
        Map<String, List<String>> found = new LinkedHashMap<>();
        // Multicast is sent out one interface at a time, so a multi-homed (Wi-Fi + Ethernet)
        // machine must probe out each local interface explicitly — otherwise a camera behind
        // the non-default one is silently missed. Bind the socket and its outgoing multicast
        // interface (IP_MULTICAST_IF) to each address in turn, with a TTL that clears the subnet.
        for (String localIp : localIpv4Addresses()) {
            MulticastSocket socket = null;
            try {
                socket = new MulticastSocket((java.net.SocketAddress) null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(localIp, 0));
                socket.setTimeToLive(MULTICAST_TTL);
                if (!localIp.equals("0.0.0.0")) {
                    NetworkInterface iface = NetworkInterface.getByInetAddress(InetAddress.getByName(localIp));
                    if (iface != null) {
                        socket.setOption(StandardSocketOptions.IP_MULTICAST_IF, iface);
                    }
                }
                socket.setSoTimeout(Math.max(1, timeoutSeconds) * 1000);
                byte[] probe = String.format(PROBE, UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(probe, probe.length,
                        InetAddress.getByName(WS_DISCOVERY_ADDR), WS_DISCOVERY_PORT));

                byte[] buffer = new byte[65535];
                while (true) {
                    DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(reply);
                    } catch (SocketTimeoutException e) {
                        break; // no more replies within the window
                    }
                    String text = new String(reply.getData(), reply.getOffset(), reply.getLength(), StandardCharsets.UTF_8);
                    List<String> xaddrs = parseXaddrs(text);
                    List<String> scopes = parseScopes(text);
                    String ip = ipFromReply(reply.getAddress().getHostAddress(), xaddrs);
                    found.putIfAbsent(ip, scopes);
                }
            } catch (IOException e) {
                // A busy/unusable interface - just skip it.
            } finally {
                if (socket != null) {
                    socket.close();
                }
            }
        }
        return found;
    }

    /** Prefer the IP in the advertised service URL (a device may answer from a different source address). */
    private static String ipFromReply(String sourceIp, List<String> xaddrs) {
        for (String xaddr : xaddrs) {
            Matcher m = HOST_IN_URL.matcher(xaddr);
            if (m.find()) {
                return m.group(1);
            }
        }
        return sourceIp;
    }

    // --- Port-scan fallback -------------------------------------------------------

    private static Map<String, List<String>> portScan() {
        List<String> targets = new ArrayList<>();
        List<String> nets = new ArrayList<>();
        for (String ip : localIpv4Addresses()) {
            if (!isIpv4(ip) || ip.equals("0.0.0.0")) {
                continue;
            }
            String net = ip.substring(0, ip.lastIndexOf('.'));
            if (nets.contains(net)) {
                continue;
            }
            nets.add(net);
            for (int host = 1; host < 255; host++) {
                targets.add(net + "." + host);
            }
        }

        Map<String, List<String>> found = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(100);
        try {
            List<java.util.concurrent.Future<String>> results = new ArrayList<>();
            for (String host : targets) {
                results.add(pool.submit(() -> hasOpenPort(host, RTSP_PORT, 1000) ? host : null));
            }
            for (java.util.concurrent.Future<String> result : results) {
                try {
                    String host = result.get();
                    if (host != null) {
                        found.put(host, List.of("onvif://www.onvif.org/hardware/RTSP camera (554 open)"));
                    }
                } catch (Exception e) {
                    // ignore a failed probe
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return found;
    }

    private static boolean hasOpenPort(String host, int port, int timeoutMillis) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // --- MAC resolution -----------------------------------------------------------

    private static String runArp() {
        try {
            Process process = new ProcessBuilder("arp", "-a").redirectErrorStream(true).start();
            byte[] out = process.getInputStream().readAllBytes();
            process.waitFor(10, TimeUnit.SECONDS);
            return new String(out, StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
    }

    /** Parses {@code arp -a} output into an IP -> normalized-MAC map (works with Windows {@code aa-bb} and Unix {@code aa:bb}). */
    static Map<String, String> parseArpTable(String arpOutput) {
        Map<String, String> table = new LinkedHashMap<>();
        for (String line : arpOutput.split("\\R")) {
            Matcher ip = IPV4.matcher(line);
            Matcher mac = MAC.matcher(line);
            if (ip.find() && mac.find()) {
                table.put(ip.group(1), normalizeMac(mac.group(1)));
            }
        }
        return table;
    }

    static String normalizeMac(String raw) {
        return raw.replace('-', ':').toUpperCase();
    }

    // --- Scope helpers (testable) -------------------------------------------------

    /** The whitespace-separated ONVIF service URLs from a WS-Discovery ProbeMatch reply. */
    static List<String> parseXaddrs(String xml) {
        return splitField(XADDRS, xml);
    }

    /** The whitespace-separated scopes (name/hardware/manufacturer URIs) from a ProbeMatch reply. */
    static List<String> parseScopes(String xml) {
        return splitField(SCOPES, xml);
    }

    private static List<String> splitField(Pattern field, String xml) {
        Matcher m = field.matcher(xml);
        if (!m.find()) {
            return List.of();
        }
        String value = m.group(1).trim();
        return value.isEmpty() ? List.of() : List.of(value.split("\\s+"));
    }

    private static Pattern fieldPattern(String tag) {
        return Pattern.compile("<[^>]*\\b" + tag + "\\b[^>]*>(.*?)</[^>]*\\b" + tag + "\\b[^>]*>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    }

    // --- Local interface enumeration ----------------------------------------------

    private static List<String> localIpv4Addresses() {
        List<String> addresses = new ArrayList<>();
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(iface.getInetAddresses())) {
                    String ip = address.getHostAddress();
                    if (isIpv4(ip) && !ip.startsWith("127.")) {
                        addresses.add(ip);
                    }
                }
            }
        } catch (IOException e) {
            // fall through to the default below
        }
        return addresses.isEmpty() ? List.of("0.0.0.0") : addresses;
    }

    private static boolean isIpv4(String ip) {
        return ip != null && IPV4.matcher(ip).matches() && ip.indexOf('%') < 0;
    }

    private static int compareIps(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        if (pa.length == 4 && pb.length == 4) {
            for (int i = 0; i < 4; i++) {
                int cmp = Integer.compare(Integer.parseInt(pa[i]), Integer.parseInt(pb[i]));
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        }
        return a.compareTo(b);
    }
}
