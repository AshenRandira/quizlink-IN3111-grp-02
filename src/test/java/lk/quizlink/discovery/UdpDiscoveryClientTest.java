package lk.quizlink.discovery;

import lk.quizlink.common.protocol.ProtocolConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdpDiscoveryClientTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final Duration TEST_TIMEOUT = Duration.ofMillis(500);
    private static final String VALID_RESPONSE =
            "{\"service\":\"QuizLink\",\"version\":1,\"tcpPort\":5000}";

    @Test
    void discoversServerEndpointUsingUdpDiscoveryServer() throws Exception {
        try (UdpDiscoveryServer server = new UdpDiscoveryServer(0, 4321)) {
            server.start();
            UdpDiscoveryClient client = new UdpDiscoveryClient(
                    LOOPBACK, server.getBoundPort(), TEST_TIMEOUT);

            DiscoveryResult result = client.discover().orElseThrow();

            assertEquals(LOOPBACK, result.serverAddress());
            assertEquals(LOOPBACK.getHostAddress(), result.serverIpAddress());
            assertEquals(4321, result.tcpPort());
        }
    }

    @Test
    void sendsExactUtf8RequestToConfiguredUnicastDestinationAndPort() throws Exception {
        try (DatagramSocket responder = responderSocket()) {
            FutureTask<byte[]> peer = startPeer(responder, request -> {
                byte[] requestBytes = new byte[request.getLength()];
                System.arraycopy(request.getData(), request.getOffset(),
                        requestBytes, 0, request.getLength());
                send(responder, request.getAddress(), request.getPort(), VALID_RESPONSE);
                return requestBytes;
            });

            Optional<DiscoveryResult> result = clientFor(responder, Duration.ofMillis(120)).discover();

            assertTrue(result.isPresent());
            assertEquals(ProtocolConstants.DISCOVERY_REQUEST,
                    new String(peer.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        }
    }

    @Test
    void returnsEmptyWhenNoServerRespondsAndClosesItsSocket() throws Exception {
        try (DatagramSocket silentServer = responderSocket()) {
            UdpDiscoveryClient client = new UdpDiscoveryClient(
                    LOOPBACK, silentServer.getLocalPort(), Duration.ofMillis(180));

            long start = System.nanoTime();
            Optional<DiscoveryResult> result = client.discover();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(result.isEmpty());
            assertTrue(elapsedMillis >= 100 && elapsedMillis < 1_000,
                    "Discovery should respect its bounded timeout; elapsed=" + elapsedMillis + "ms");
        }
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void ignoresInvalidResponsesUntilTimeout(String invalidResponse) throws Exception {
        try (DatagramSocket responder = responderSocket()) {
            FutureTask<Void> peer = startPeer(responder, request -> {
                send(responder, request.getAddress(), request.getPort(), invalidResponse);
                return null;
            });

            Optional<DiscoveryResult> result = clientFor(responder, Duration.ofMillis(120)).discover();

            peer.get(2, TimeUnit.SECONDS);
            assertTrue(result.isEmpty(), "Response should be rejected: " + invalidResponse);
        }
    }

    @Test
    void ignoresInvalidAndOversizedResponsesThenAcceptsNextValidResponse() throws Exception {
        try (DatagramSocket responder = responderSocket()) {
            FutureTask<Void> peer = startPeer(responder, request -> {
                send(responder, request.getAddress(), request.getPort(), "{broken");
                send(responder, request.getAddress(), request.getPort(),
                        "x".repeat(4_200));
                send(responder, request.getAddress(), request.getPort(), VALID_RESPONSE);
                return null;
            });

            DiscoveryResult result = clientFor(responder).discover().orElseThrow();

            peer.get(2, TimeUnit.SECONDS);
            assertEquals(5000, result.tcpPort());
            assertEquals(LOOPBACK, result.serverAddress());
        }
    }

    @Test
    void invalidPacketFloodDoesNotExtendOverallDeadline() throws Exception {
        try (DatagramSocket responder = responderSocket()) {
            FutureTask<Void> peer = startPeer(responder, request -> {
                for (int i = 0; i < 35; i++) {
                    send(responder, request.getAddress(), request.getPort(),
                            "{\"service\":\"Wrong\",\"version\":1,\"tcpPort\":5000}");
                    Thread.sleep(15);
                }
                return null;
            });
            UdpDiscoveryClient client = new UdpDiscoveryClient(
                    LOOPBACK, responder.getLocalPort(), Duration.ofMillis(250));

            long start = System.nanoTime();
            Optional<DiscoveryResult> result = client.discover();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            peer.get(2, TimeUnit.SECONDS);
            assertTrue(result.isEmpty());
            assertTrue(elapsedMillis < 700,
                    "Invalid traffic must not restart the deadline; elapsed=" + elapsedMillis + "ms");
        }
    }

    @Test
    void returnsFirstValidResponseWhenSeveralServersRespond() throws Exception {
        try (DatagramSocket responder = responderSocket()) {
            FutureTask<Void> peer = startPeer(responder, request -> {
                send(responder, request.getAddress(), request.getPort(),
                        "{\"service\":\"QuizLink\",\"version\":1,\"tcpPort\":4101}");
                send(responder, request.getAddress(), request.getPort(),
                        "{\"service\":\"QuizLink\",\"version\":1,\"tcpPort\":4102}");
                return null;
            });

            DiscoveryResult result = clientFor(responder).discover().orElseThrow();

            peer.get(2, TimeUnit.SECONDS);
            assertEquals(4101, result.tcpPort());
        }
    }

    @Test
    void repeatedDiscoveriesUseIndependentSocketsAndSucceed() throws Exception {
        try (UdpDiscoveryServer server = new UdpDiscoveryServer(0, 5000)) {
            server.start();
            UdpDiscoveryClient client = new UdpDiscoveryClient(
                    LOOPBACK, server.getBoundPort(), TEST_TIMEOUT);

            assertTrue(client.discover().isPresent());
            assertTrue(client.discover().isPresent());
        }
    }

    @Test
    void rejectsInvalidDestinationPortAndTimeoutConfiguration() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new UdpDiscoveryClient(0, TEST_TIMEOUT));
        assertThrows(IllegalArgumentException.class,
                () -> new UdpDiscoveryClient(65_536, TEST_TIMEOUT));
        assertThrows(IllegalArgumentException.class,
                () -> new UdpDiscoveryClient(null, 5001, TEST_TIMEOUT));
        assertThrows(IllegalArgumentException.class,
                () -> new UdpDiscoveryClient(InetAddress.getByName("0.0.0.0"), 5001, TEST_TIMEOUT));
        assertThrows(IllegalArgumentException.class,
                () -> new UdpDiscoveryClient(InetAddress.getByName("239.1.1.1"), 5001, TEST_TIMEOUT));
        assertThrows(IllegalArgumentException.class,
                () -> new UdpDiscoveryClient(5001, null));
        assertThrows(IllegalArgumentException.class,
                () -> new UdpDiscoveryClient(5001, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new UdpDiscoveryClient(5001, Duration.ofNanos(500_000)));
        assertThrows(IllegalArgumentException.class,
                () -> new UdpDiscoveryClient(5001, Duration.ofDays(30)));
    }

    @Test
    void discoveryResultValidatesItsAddressAndPort() {
        assertThrows(NullPointerException.class, () -> new DiscoveryResult(null, 5000));
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryResult(LOOPBACK, 0));
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryResult(LOOPBACK, 65_536));
    }

    static Stream<String> invalidResponses() {
        return Stream.of(
                "{",
                "[]",
                "\"QuizLink\"",
                "null",
                "/*comment*/" + VALID_RESPONSE,
                "{'service':'QuizLink','version':1,'tcpPort':5000}",
                "{\"service\":\"Other\",\"version\":1,\"tcpPort\":5000}",
                "{\"service\":\"QuizLink\",\"version\":2,\"tcpPort\":5000}",
                "{\"service\":\"QuizLink\",\"version\":1}",
                "{\"version\":1,\"tcpPort\":5000}",
                "{\"service\":null,\"version\":1,\"tcpPort\":5000}",
                "{\"service\":\"QuizLink\",\"version\":null,\"tcpPort\":5000}",
                "{\"service\":\"QuizLink\",\"version\":1,\"tcpPort\":null}",
                "{\"service\":\"QuizLink\",\"version\":\"1\",\"tcpPort\":5000}",
                "{\"service\":\"QuizLink\",\"version\":true,\"tcpPort\":5000}",
                "{\"service\":\"QuizLink\",\"version\":1.0,\"tcpPort\":5000}",
                "{\"service\":\"QuizLink\",\"version\":1,\"tcpPort\":\"5000\"}",
                "{\"service\":\"QuizLink\",\"version\":1,\"tcpPort\":true}",
                "{\"service\":\"QuizLink\",\"version\":1,\"tcpPort\":5000.5}",
                "{\"service\":\"QuizLink\",\"version\":1,\"tcpPort\":5e3}",
                "{\"service\":\"QuizLink\",\"version\":1,\"tcpPort\":-1}",
                "{\"service\":\"QuizLink\",\"version\":1,\"tcpPort\":0}",
                "{\"service\":\"QuizLink\",\"version\":1,\"tcpPort\":65536}",
                "{\"service\":\"QuizLink\",\"version\":999999999999999999999,\"tcpPort\":5000}",
                "{\"service\":\"QuizLink\",\"version\":1,\"tcpPort\":999999999999999999999}",
                "{\"service\":\"QuizLink\",\"version\":1,\"tcpPort\":5000} trailing");
    }

    private static UdpDiscoveryClient clientFor(DatagramSocket responder) {
        return new UdpDiscoveryClient(LOOPBACK, responder.getLocalPort(), TEST_TIMEOUT);
    }

    private static UdpDiscoveryClient clientFor(DatagramSocket responder, Duration timeout) {
        return new UdpDiscoveryClient(LOOPBACK, responder.getLocalPort(), timeout);
    }

    private static DatagramSocket responderSocket() throws IOException {
        DatagramSocket socket = new DatagramSocket(null);
        socket.bind(new InetSocketAddress(LOOPBACK, 0));
        socket.setSoTimeout(1_000);
        return socket;
    }

    private static <T> FutureTask<T> startPeer(
            DatagramSocket responder, PeerAction<T> action) {
        FutureTask<T> task = new FutureTask<>(() ->
                action.run(receiveRequest(responder)));
        Thread thread = new Thread(task, "udp-discovery-client-test-peer");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    private static DatagramPacket receiveRequest(DatagramSocket socket) throws IOException {
        byte[] bytes = new byte[256];
        DatagramPacket request = new DatagramPacket(bytes, bytes.length);
        socket.receive(request);
        return request;
    }

    private static void send(DatagramSocket socket, InetAddress address, int port, String text)
            throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(bytes, bytes.length, address, port));
    }

    @FunctionalInterface
    private interface PeerAction<T> {
        T run(DatagramPacket request) throws Exception;
    }
}
