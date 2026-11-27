package lk.quizlink.discovery;

import lk.quizlink.common.protocol.ProtocolConstants;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Optional;

/**
 * Demonstrates automatic LAN broadcast or explicitly targeted UDP discovery.
 * <p>
 * With no arguments this uses {@link UdpDiscoveryClient}'s default broadcast configuration.
 * Direct mode is intended for same-computer diagnostics and is not LAN broadcast discovery.
 * Neither mode connects to the advertised TCP port.
 */
public final class DiscoveryClientDemo {

    private DiscoveryClientDemo() {
    }

    /**
     * Runs the requested discovery mode and reports success, timeout, or networking errors.
     *
     * @param args empty for LAN broadcast, or {@code --direct <address> [port] [timeout-ms]}
     */
    public static void main(String[] args) {
        DemoOptions options;
        try {
            options = parseArguments(args);
        } catch (IllegalArgumentException exception) {
            System.err.println("Invalid discovery client arguments: " + exception.getMessage());
            printUsage();
            return;
        }

        System.out.println("QuizLink UDP Discovery Client");
        System.out.println("Discovery mode: " + (options.direct() ? "direct unicast" : "LAN broadcast"));
        if (options.direct()) {
            System.out.println("Direct destination: " + options.destination().getHostAddress());
        }
        System.out.println("UDP discovery port: " + options.discoveryPort());
        System.out.println("Sending discovery request...");

        UdpDiscoveryClient client = options.direct()
                ? new UdpDiscoveryClient(options.destination(), options.discoveryPort(), options.timeout())
                : new UdpDiscoveryClient(options.discoveryPort(), options.timeout());
        try {
            Optional<DiscoveryResult> discovered = client.discover();
            if (discovered.isPresent()) {
                DiscoveryResult result = discovered.get();
                System.out.println("QuizLink server discovered successfully.");
                System.out.println("Server IP address: " + result.serverIpAddress());
                System.out.println("Advertised TCP port: " + result.tcpPort());
                System.out.println("Discovery does not confirm that the TCP server is running.");
            } else {
                System.out.println("No valid server response received before the timeout.");
                System.out.println("Manual server address entry remains available in the Student application.");
            }
            System.out.println("UDP discovery completed.");
        } catch (IOException exception) {
            System.err.println("UDP discovery failed: " + exception.getMessage());
        }
    }

    static DemoOptions parseArguments(String[] args) {
        if (args == null) {
            throw new IllegalArgumentException("arguments are required");
        }
        if (args.length == 0) {
            return new DemoOptions(false, null, ProtocolConstants.DISCOVERY_PORT,
                    UdpDiscoveryClient.DEFAULT_TIMEOUT);
        }
        if (!"--direct".equals(args[0])) {
            throw new IllegalArgumentException("unknown mode");
        }
        if (args.length < 2 || args.length > 4) {
            throw new IllegalArgumentException("direct mode requires an address and accepts optional port and timeout");
        }

        InetAddress destination;
        try {
            destination = InetAddress.getByName(args[1]);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("destination address could not be resolved", exception);
        }
        int port = args.length >= 3
                ? parseInteger(args[2], "discovery port")
                : ProtocolConstants.DISCOVERY_PORT;
        Duration timeout = args.length == 4
                ? Duration.ofMillis(parseInteger(args[3], "timeout"))
                : UdpDiscoveryClient.DEFAULT_TIMEOUT;

        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("discovery port must be between 1 and 65535");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        try {
            new UdpDiscoveryClient(destination, port, timeout);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
        return new DemoOptions(true, destination, port, timeout);
    }

    private static int parseInteger(String value, String description) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(description + " must be an integer", exception);
        }
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  DiscoveryClientDemo");
        System.err.println("  DiscoveryClientDemo --direct <address> [discovery-port] [timeout-ms]");
    }

    record DemoOptions(boolean direct, InetAddress destination, int discoveryPort, Duration timeout) {
    }
}
