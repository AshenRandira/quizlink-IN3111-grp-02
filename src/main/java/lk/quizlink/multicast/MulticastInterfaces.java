package lk.quizlink.multicast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility for discovering and selecting network interfaces suitable for LAN multicast.
 */
public final class MulticastInterfaces {

    private static final Logger LOGGER = Logger.getLogger(MulticastInterfaces.class.getName());

    private MulticastInterfaces() {
    }

    /**
     * Picks the default network interface for multicast.
     * Selects the first interface that is up, supports multicast, is not loopback,
     * and has at least one IPv4 address. If none matches, falls back to the loopback interface.
     *
     * @return a suitable NetworkInterface, or null if none can be found
     */
    public static NetworkInterface defaultInterface() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface netIf = interfaces.nextElement();
                    if (isUsable(netIf) && !netIf.isLoopback()) {
                        return netIf;
                    }
                }
            }
            return findLoopbackInterface();
        } catch (SocketException e) {
            LOGGER.log(Level.WARNING, "Failed to enumerate network interfaces", e);
            return null;
        }
    }

    /**
     * Lists all usable network interfaces that are up and have at least one IPv4 address.
     * This method allows the host UI to let the user select a specific LAN interface.
     *
     * @return an unmodifiable list of usable network interfaces
     */
    public static List<NetworkInterface> listUsableInterfaces() {
        List<NetworkInterface> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface netIf = interfaces.nextElement();
                    if (isUsable(netIf)) {
                        result.add(netIf);
                    }
                }
            }
        } catch (SocketException e) {
            LOGGER.log(Level.WARNING, "Failed to list usable network interfaces", e);
        }
        return List.copyOf(result);
    }

    private static boolean isUsable(NetworkInterface netIf) {
        try {
            return netIf.isUp() && hasIpv4Address(netIf) && (netIf.supportsMulticast() || netIf.isLoopback());
        } catch (SocketException e) {
            return false;
        }
    }

    private static boolean hasIpv4Address(NetworkInterface netIf) {
        Enumeration<InetAddress> addresses = netIf.getInetAddresses();
        while (addresses.hasMoreElements()) {
            if (addresses.nextElement() instanceof Inet4Address) {
                return true;
            }
        }
        return false;
    }

    private static NetworkInterface findLoopbackInterface() {
        try {
            NetworkInterface loopback = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
            if (loopback != null && loopback.isUp()) {
                return loopback;
            }
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface netIf = interfaces.nextElement();
                    if (netIf.isUp() && netIf.isLoopback()) {
                        return netIf;
                    }
                }
            }
        } catch (SocketException e) {
            LOGGER.log(Level.FINE, "Failed to locate loopback interface", e);
        }
        return null;
    }
}
