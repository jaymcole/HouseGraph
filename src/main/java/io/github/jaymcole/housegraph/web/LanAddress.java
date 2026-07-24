package io.github.jaymcole.housegraph.web;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/**
 * Picks the local IPv4 address to advertise over mDNS — shared by every web-hosting resource
 * ({@link LocalWebServer}, {@link NodeProcessServer}) so the choice is made one way. jmdns
 * needs a concrete interface address, not the wildcard, and it must be the address the LAN can
 * actually reach: getting it wrong means {@code <name>.local} resolves to an unroutable address
 * even though the server is up.
 * <p>
 * The reliable way is to ask the OS which source address it would use to reach the network: a
 * UDP socket "connected" to a remote address does a route lookup (without sending anything) and
 * binds to the chosen source. That picks the real Wi-Fi/Ethernet interface and sidesteps virtual
 * adapters (WSL, Hyper-V) and link-local ({@code 169.254.x}) addresses that
 * {@link NetworkInterface} enumeration can't reliably tell apart ({@code isVirtual()} only flags
 * sub-interfaces, not virtual NICs). Falls back to interface enumeration, then the default local
 * host, if there's no route.
 */
final class LanAddress {

    private static final Logger log = Log.get(LanAddress.class);

    private LanAddress() {
    }

    static InetAddress siteLocal() throws IOException {
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
}
