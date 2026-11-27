package lk.quizlink.discovery;

import lk.quizlink.common.protocol.ProtocolConstants;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * Standalone demonstration of the UDP discovery service.
 * <p>
 * This demo starts only the UDP discovery listener; it does not start or claim to start the
 * central TCP server. Press Ctrl+C to stop the service and close its socket.
 */
public final class DiscoveryServerDemo {

    private DiscoveryServerDemo() {
    }

    /**
     * Starts the discovery service and keeps this demonstration process alive until termination.
     *
     * @param args unused
     * @throws IOException if the configured UDP port cannot be bound
     * @throws InterruptedException if the demonstration wait is interrupted
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        try (UdpDiscoveryServer server = new UdpDiscoveryServer()) {
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "quizlink-discovery-shutdown"));

            System.out.println("QuizLink UDP Discovery Server started.");
            System.out.println("Listening on UDP port: " + server.getBoundPort());
            System.out.println("Advertised TCP port: " + ProtocolConstants.TCP_PORT);
            System.out.println("The central TCP server is not started by this demonstration.");
            System.out.println("Waiting for discovery requests...");
            System.out.println("Press Ctrl+C to stop.");

            new CountDownLatch(1).await();
        }
    }
}
