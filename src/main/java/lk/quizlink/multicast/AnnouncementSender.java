package lk.quizlink.multicast;

import com.google.gson.JsonObject;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageCodec;
import lk.quizlink.common.protocol.MessageType;
import lk.quizlink.common.protocol.ProtocolConstants;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends announcements over UDP multicast to hosts and students on the local network.
 * <p>
 * Packets are restricted to the local network segment (TTL = 1) and sent over a
 * selected network interface.
 */
public final class AnnouncementSender implements AutoCloseable {

    public static final int MAX_DATAGRAM_SIZE = 1024;

    private final InetAddress group;
    private final int port;
    private final NetworkInterface networkInterface;
    private final MulticastSocket socket;
    private final MessageCodec codec;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Creates an AnnouncementSender configured with the standard protocol multicast
     * group (239.10.10.10), port (5002), and the default detected network interface.
     *
     * @throws IOException if the multicast socket cannot be opened or configured
     */
    public AnnouncementSender() throws IOException {
        this(InetAddress.getByName(ProtocolConstants.MULTICAST_GROUP),
                ProtocolConstants.MULTICAST_PORT,
                MulticastInterfaces.defaultInterface());
    }

    /**
     * Creates an AnnouncementSender targeting a specified group address string, port, and interface.
     *
     * @param groupAddress IP address string of the multicast group
     * @param port the target multicast UDP port
     * @param networkInterface the outgoing network interface (or null for system default)
     * @throws IOException if the address cannot be resolved or socket cannot be opened
     */
    public AnnouncementSender(String groupAddress, int port, NetworkInterface networkInterface) throws IOException {
        this(InetAddress.getByName(Objects.requireNonNull(groupAddress, "groupAddress must not be null")),
                port, networkInterface);
    }

    /**
     * Creates an AnnouncementSender with explicit group address, port, and network interface.
     *
     * @param group the multicast group InetAddress
     * @param port the target UDP port
     * @param networkInterface the network interface to bind outgoing packets to
     * @throws IOException if socket creation or interface selection fails
     */
    public AnnouncementSender(InetAddress group, int port, NetworkInterface networkInterface) throws IOException {
        this.group = Objects.requireNonNull(group, "group must not be null");
        if (!group.isMulticastAddress()) {
            throw new IllegalArgumentException("Address is not a multicast group: " + group);
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port out of range: " + port);
        }
        this.port = port;
        this.networkInterface = networkInterface;
        this.codec = new MessageCodec();

        this.socket = new MulticastSocket();
        this.socket.setTimeToLive(1); // Restrict to local LAN only
        if (networkInterface != null) {
            this.socket.setNetworkInterface(networkInterface);
        }
    }

    /**
     * Encodes and sends an announcement datagram over multicast.
     *
     * @param announcement the announcement to send
     * @throws IOException if a network send failure occurs
     * @throws IllegalArgumentException if announcement is null or serialized payload exceeds 1024 bytes
     * @throws IllegalStateException if the sender is closed
     */
    public void send(Announcement announcement) throws IOException {
        if (announcement == null) {
            throw new IllegalArgumentException("announcement must not be null");
        }
        if (closed.get() || socket.isClosed()) {
            throw new IllegalStateException("AnnouncementSender is closed");
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("roomCode", announcement.roomCode());
        payload.addProperty("text", announcement.text());
        Message message = Message.of(MessageType.ANNOUNCEMENT, "", payload);

        String jsonLine = codec.encode(message);
        byte[] bytes = jsonLine.getBytes(StandardCharsets.UTF_8);

        if (bytes.length > MAX_DATAGRAM_SIZE) {
            throw new IllegalArgumentException(
                    "Announcement payload exceeds maximum allowed size (" +
                    bytes.length + " > " + MAX_DATAGRAM_SIZE + " bytes)");
        }

        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, group, port);
        socket.send(packet);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            socket.close();
        }
    }

    public boolean isClosed() {
        return closed.get() || socket.isClosed();
    }
}
