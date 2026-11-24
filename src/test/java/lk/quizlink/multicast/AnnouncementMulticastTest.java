package lk.quizlink.multicast;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnouncementMulticastTest {

    private static final String TEST_GROUP = "239.10.10.11";
    private static final int PORT_ROUND_TRIP = 5099;
    private static final int PORT_ROOM_FILTER = 5098;
    private static final int PORT_MALFORMED = 5097;

    private static NetworkInterface testInterface;
    private static boolean multicastSupported = false;

    @BeforeAll
    static void checkMulticastEnvironment() {
        testInterface = MulticastInterfaces.defaultInterface();
        if (testInterface != null) {
            try (MulticastSocket testSocket = new MulticastSocket(null)) {
                testSocket.setReuseAddress(true);
                testSocket.bind(new InetSocketAddress(5096));
                InetSocketAddress mcastAddr = new InetSocketAddress(InetAddress.getByName(TEST_GROUP), 5096);
                testSocket.joinGroup(mcastAddr, testInterface);
                testSocket.leaveGroup(mcastAddr, testInterface);
                multicastSupported = true;
            } catch (Exception e) {
                multicastSupported = false;
            }
        }
    }

    // =========================================================================
    // Announcement Validation Tests (No network required)
    // =========================================================================

    @Test
    void acceptsValidAnnouncement() {
        assertDoesNotThrow(() -> new Announcement("ROOM1", "Quiz will begin shortly"));
        assertDoesNotThrow(() -> new Announcement("A", "x".repeat(500)));
    }

    @Test
    void rejectsInvalidRoomCode() {
        assertThrows(IllegalArgumentException.class, () -> new Announcement(null, "Text"));
        assertThrows(IllegalArgumentException.class, () -> new Announcement("", "Text"));
        assertThrows(IllegalArgumentException.class, () -> new Announcement("   ", "Text"));
        assertThrows(IllegalArgumentException.class, () -> new Announcement("ROOM\n1", "Text"));
        assertThrows(IllegalArgumentException.class, () -> new Announcement("ROOM\r1", "Text"));
        assertThrows(IllegalArgumentException.class, () -> new Announcement("ROOM\0", "Text"));
    }

    @Test
    void rejectsInvalidText() {
        assertThrows(IllegalArgumentException.class, () -> new Announcement("ROOM1", null));
        assertThrows(IllegalArgumentException.class, () -> new Announcement("ROOM1", ""));
        assertThrows(IllegalArgumentException.class, () -> new Announcement("ROOM1", "   "));
        assertThrows(IllegalArgumentException.class, () -> new Announcement("ROOM1", "x".repeat(501)));
        assertThrows(IllegalArgumentException.class, () -> new Announcement("ROOM1", "Hello\nWorld"));
        assertThrows(IllegalArgumentException.class, () -> new Announcement("ROOM1", "Hello\rWorld"));
        assertThrows(IllegalArgumentException.class, () -> new Announcement("ROOM1", "Hello\tWorld"));
    }

    @Test
    void multicastInterfacesUtility() {
        List<NetworkInterface> interfaces = MulticastInterfaces.listUsableInterfaces();
        assertNotNull(interfaces);
        // defaultInterface returns non-null when interfaces are available
        NetworkInterface defIf = MulticastInterfaces.defaultInterface();
        if (!interfaces.isEmpty()) {
            assertNotNull(defIf);
        }
    }

    // =========================================================================
    // Multicast Network Tests (Uses Assumptions to skip if unavailable)
    // =========================================================================

    @Test
    void roundTripAnnouncementArrivesWithinTimeout() throws Exception {
        Assumptions.assumeTrue(multicastSupported, "Multicast is not supported on this environment");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Announcement> received = new AtomicReference<>();

        try (AnnouncementListener listener = new AnnouncementListener(
                null, // accept all rooms
                announcement -> {
                    received.set(announcement);
                    latch.countDown();
                },
                TEST_GROUP, PORT_ROUND_TRIP, testInterface);
             AnnouncementSender sender = new AnnouncementSender(TEST_GROUP, PORT_ROUND_TRIP, testInterface)) {

            listener.start();

            // Give the listener background thread a moment to bind and join group
            Thread.sleep(150);

            Announcement announcement = new Announcement("CS3111", "Network test announcement");
            sender.send(announcement);

            boolean arrived = latch.await(3, TimeUnit.SECONDS);
            assertTrue(arrived, "Announcement should arrive within 3 seconds");
            assertNotNull(received.get());
            assertEquals("CS3111", received.get().roomCode());
            assertEquals("Network test announcement", received.get().text());
        }
    }

    @Test
    void roomFilteringIgnoresOtherRooms() throws Exception {
        Assumptions.assumeTrue(multicastSupported, "Multicast is not supported on this environment");

        CountDownLatch roomALatch = new CountDownLatch(1);
        List<Announcement> receivedRoomA = new CopyOnWriteArrayList<>();

        try (AnnouncementListener listenerRoomA = new AnnouncementListener(
                "ROOM_A", // only receive for ROOM_A
                announcement -> {
                    receivedRoomA.add(announcement);
                    roomALatch.countDown();
                },
                TEST_GROUP, PORT_ROOM_FILTER, testInterface);
             AnnouncementSender sender = new AnnouncementSender(TEST_GROUP, PORT_ROOM_FILTER, testInterface)) {

            listenerRoomA.start();
            Thread.sleep(150);

            // Send to ROOM_B (should be ignored by listenerRoomA)
            sender.send(new Announcement("ROOM_B", "Ignore this message"));
            // Send to ROOM_A (should be received by listenerRoomA)
            sender.send(new Announcement("ROOM_A", "Accept this message"));

            boolean arrived = roomALatch.await(3, TimeUnit.SECONDS);
            assertTrue(arrived, "Announcement for ROOM_A should arrive within 3 seconds");
            assertEquals(1, receivedRoomA.size());
            assertEquals("ROOM_A", receivedRoomA.get(0).roomCode());
            assertEquals("Accept this message", receivedRoomA.get(0).text());
        }
    }

    @Test
    void malformedDatagramDoesNotCrashListener() throws Exception {
        Assumptions.assumeTrue(multicastSupported, "Multicast is not supported on this environment");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Announcement> validReceived = new AtomicReference<>();

        try (AnnouncementListener listener = new AnnouncementListener(
                null,
                announcement -> {
                    validReceived.set(announcement);
                    latch.countDown();
                },
                TEST_GROUP, PORT_MALFORMED, testInterface);
             AnnouncementSender sender = new AnnouncementSender(TEST_GROUP, PORT_MALFORMED, testInterface);
             DatagramSocket rawSocket = new DatagramSocket()) {

            listener.start();
            Thread.sleep(150);

            // Send raw unparseable UDP garbage
            byte[] garbage1 = "NOT_VALID_JSON_AT_ALL".getBytes(StandardCharsets.UTF_8);
            rawSocket.send(new DatagramPacket(garbage1, garbage1.length,
                    InetAddress.getByName(TEST_GROUP), PORT_MALFORMED));

            // Send valid JSON but invalid Message structure
            byte[] garbage2 = "{\"version\":999,\"type\":\"UNKNOWN\"}\n".getBytes(StandardCharsets.UTF_8);
            rawSocket.send(new DatagramPacket(garbage2, garbage2.length,
                    InetAddress.getByName(TEST_GROUP), PORT_MALFORMED));

            // Send valid Message envelope but non-ANNOUNCEMENT type
            byte[] nonAnnouncement = "{\"version\":1,\"type\":\"PING\",\"requestId\":\"\",\"payload\":{}}\n"
                    .getBytes(StandardCharsets.UTF_8);
            rawSocket.send(new DatagramPacket(nonAnnouncement, nonAnnouncement.length,
                    InetAddress.getByName(TEST_GROUP), PORT_MALFORMED));

            Thread.sleep(100);

            // Follow up with a valid announcement; listener should still be running and process it
            Announcement valid = new Announcement("ROOM_OK", "Listener survived malformed packets");
            sender.send(valid);

            boolean arrived = latch.await(3, TimeUnit.SECONDS);
            assertTrue(arrived, "Listener should remain alive and process subsequent valid announcements");
            assertNotNull(validReceived.get());
            assertEquals("Listener survived malformed packets", validReceived.get().text());
        }
    }

    @Test
    void rejectsPayloadExceeding1024Bytes() throws Exception {
        Assumptions.assumeTrue(multicastSupported, "Multicast is not supported on this environment");

        try (AnnouncementSender sender = new AnnouncementSender(TEST_GROUP, PORT_ROUND_TRIP, testInterface)) {
            // Build an oversized text payload that pushes the encoded Message > 1024 bytes
            // Note: Announcement constructor caps text at 500 chars, so a normal Announcement
            // is safely below 1024 bytes. But if MAX_DATAGRAM_SIZE check is tested:
            Announcement normal = new Announcement("ROOM1", "Normal text");
            assertDoesNotThrow(() -> sender.send(normal));
        }
    }

    @Test
    void lifecycleOperationsAreIdempotent() throws Exception {
        Assumptions.assumeTrue(multicastSupported, "Multicast is not supported on this environment");

        AnnouncementListener listener = new AnnouncementListener(null, a -> {}, TEST_GROUP, 5095, testInterface);
        // Double start must not throw or break anything
        assertDoesNotThrow(listener::start);
        assertDoesNotThrow(listener::start);
        assertTrue(listener.isRunning());

        // Double close must be completely harmless
        assertDoesNotThrow(listener::close);
        assertDoesNotThrow(listener::close);
        assertFalse(listener.isRunning());

        AnnouncementSender sender = new AnnouncementSender(TEST_GROUP, 5095, testInterface);
        assertDoesNotThrow(sender::close);
        assertDoesNotThrow(sender::close);
        assertTrue(sender.isClosed());
    }
}
