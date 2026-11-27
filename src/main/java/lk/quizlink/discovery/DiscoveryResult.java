package lk.quizlink.discovery;

import java.net.InetAddress;
import java.util.Objects;

/**
 * The server endpoint advertised by a valid QuizLink UDP discovery response.
 *
 * @param serverAddress source address of the response datagram
 * @param tcpPort advertised TCP port
 */
public record DiscoveryResult(InetAddress serverAddress, int tcpPort) {

    /**
     * Validates the address and advertised TCP port.
     *
     * @throws NullPointerException if {@code serverAddress} is null
     * @throws IllegalArgumentException if {@code tcpPort} is outside 1-65535
     */
    public DiscoveryResult {
        Objects.requireNonNull(serverAddress, "serverAddress must not be null");
        if (tcpPort < 1 || tcpPort > 65_535) {
            throw new IllegalArgumentException("TCP port must be between 1 and 65535");
        }
    }

    /**
     * Returns the numeric server IP address without performing a hostname lookup.
     *
     * @return numeric IP address
     */
    public String serverIpAddress() {
        return serverAddress.getHostAddress();
    }
}
