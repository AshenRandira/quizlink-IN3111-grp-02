package lk.quizlink.discovery;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lk.quizlink.common.protocol.ProtocolConstants;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdpDiscoveryServerTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final int CLIENT_TIMEOUT_MILLIS = 250;

    @Test
    void startsOnEphemeralPortAndReportsRunningState() throws Exception {
        try (UdpDiscoveryServer server = new UdpDiscoveryServer(0, 5000)) {
            assertFalse(server.isRunning());
            server.start();
            assertTrue(server.isRunning());
            assertTrue(server.getBoundPort() > 0);
        }
    }

    @Test
    void validRequestGetsJsonResponseAtRequestingClientPort() throws Exception {
        try (UdpDiscoveryServer server = new UdpDiscoveryServer(0, 4321);
             DatagramSocket client = clientSocket()) {
            server.start();

            send(client, ProtocolConstants.DISCOVERY_REQUEST, server.getBoundPort());
            DatagramPacket received = receive(client);
            JsonObject response = JsonParser.parseString(
                    new String(received.getData(), received.getOffset(), received.getLength(),
                            StandardCharsets.UTF_8)).getAsJsonObject();

            assertEquals(LOOPBACK, received.getAddress());
            assertEquals(server.getBoundPort(), received.getPort());
            assertTrue(client.getLocalPort() > 0, "The response was received on the request socket");
            assertEquals("QuizLink", response.get("service").getAsString());
            assertEquals(ProtocolConstants.VERSION, response.get("version").getAsInt());
            assertEquals(4321, response.get("tcpPort").getAsInt());
        }
    }

    @Test
    void ignoresEmptyMalformedUnsupportedAndOversizedRequestsThenContinues() throws Exception {
        try (UdpDiscoveryServer server = new UdpDiscoveryServer(0, 5000);
             DatagramSocket client = clientSocket()) {
            server.start();

            send(client, new byte[0], server.getBoundPort());
            assertNoResponse(client);
            send(client, new byte[]{(byte) 0xC3, (byte) 0x28}, server.getBoundPort());
            assertNoResponse(client);
            send(client, "NOT_A_QUIZLINK_REQUEST", server.getBoundPort());
            assertNoResponse(client);
            send(client, "QUIZLINK_DISCOVER_V2", server.getBoundPort());
            assertNoResponse(client);
            send(client, ProtocolConstants.DISCOVERY_REQUEST + "x".repeat(1024), server.getBoundPort());
            assertNoResponse(client);

            send(client, ProtocolConstants.DISCOVERY_REQUEST, server.getBoundPort());
            DatagramPacket response = receive(client);
            assertEquals("QuizLink", JsonParser.parseString(
                    new String(response.getData(), response.getOffset(), response.getLength(),
                            StandardCharsets.UTF_8)).getAsJsonObject().get("service").getAsString());
            assertTrue(server.isRunning());
        }
    }

    @Test
    void respondsIndependentlyToMultipleClients() throws Exception {
        try (UdpDiscoveryServer server = new UdpDiscoveryServer(0, 5000);
             DatagramSocket firstClient = clientSocket();
             DatagramSocket secondClient = clientSocket()) {
            server.start();

            send(firstClient, ProtocolConstants.DISCOVERY_REQUEST, server.getBoundPort());
            send(secondClient, ProtocolConstants.DISCOVERY_REQUEST, server.getBoundPort());

            assertEquals(server.getBoundPort(), receive(firstClient).getPort());
            assertEquals(server.getBoundPort(), receive(secondClient).getPort());
        }
    }

    @Test
    void stopIsIdempotentReleasesSocketAndAllowsRestart() throws Exception {
        UdpDiscoveryServer server = new UdpDiscoveryServer(0, 5000);
        try {
            server.start();
            int firstPort = server.getBoundPort();
            server.start();
            assertEquals(firstPort, server.getBoundPort());

            server.stop();
            server.stop();
            assertFalse(server.isRunning());
            assertThrows(IllegalStateException.class, server::getBoundPort);

            try (DatagramSocket releasedPort = new DatagramSocket(null)) {
                releasedPort.bind(new InetSocketAddress(firstPort));
            }

            server.start();
            assertTrue(server.isRunning());
        } finally {
            server.close();
        }
        assertFalse(server.isRunning());
    }

    @Test
    void rejectsInvalidPorts() {
        assertThrows(IllegalArgumentException.class, () -> new UdpDiscoveryServer(-1, 5000));
        assertThrows(IllegalArgumentException.class, () -> new UdpDiscoveryServer(65_536, 5000));
        assertThrows(IllegalArgumentException.class, () -> new UdpDiscoveryServer(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new UdpDiscoveryServer(0, 65_536));
    }

    @Test
    void bindFailureDoesNotMarkServiceRunning() throws Exception {
        try (DatagramSocket occupiedPort = new DatagramSocket(0)) {
            UdpDiscoveryServer server = new UdpDiscoveryServer(occupiedPort.getLocalPort(), 5000);
            try {
                assertThrows(IOException.class, server::start);
                assertFalse(server.isRunning());
                assertThrows(IllegalStateException.class, server::getBoundPort);
            } finally {
                server.close();
            }
        }
    }

    private static DatagramSocket clientSocket() throws IOException {
        DatagramSocket socket = new DatagramSocket(0, LOOPBACK);
        socket.setSoTimeout(CLIENT_TIMEOUT_MILLIS);
        return socket;
    }

    private static void send(DatagramSocket socket, String text, int port) throws IOException {
        send(socket, text.getBytes(StandardCharsets.UTF_8), port);
    }

    private static void send(DatagramSocket socket, byte[] bytes, int port) throws IOException {
        socket.send(new DatagramPacket(bytes, bytes.length, LOOPBACK, port));
    }

    private static DatagramPacket receive(DatagramSocket socket) throws IOException {
        byte[] responseBuffer = new byte[512];
        DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
        socket.receive(response);
        return response;
    }

    private static void assertNoResponse(DatagramSocket socket) throws IOException {
        assertThrows(SocketTimeoutException.class, () -> receive(socket));
    }
}
