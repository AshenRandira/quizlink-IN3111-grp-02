package lk.quizlink.multicast;

import com.google.gson.JsonObject;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageCodec;
import lk.quizlink.common.protocol.MessageType;
import lk.quizlink.common.protocol.ProtocolConstants;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Listens for multicast room announcements on a daemon background thread.
 * <p>
 * Multiple listeners can run simultaneously on the same machine due to {@code SO_REUSEADDR}
 * configuration. Incoming datagrams are parsed with {@link MessageCodec}; malformed
 * messages or messages for other rooms are discarded without interrupting the listener.
 * <p>
 * <b>Threading Notice:</b> The {@link Consumer} callback is invoked on the internal listener
 * background thread, <b>NOT</b> on the Swing Event Dispatch Thread (EDT). Any UI updates
 * performed in the callback must be wrapped in {@code javax.swing.SwingUtilities.invokeLater}.
 */
public final class AnnouncementListener implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(AnnouncementListener.class.getName());
    private static final int BUFFER_SIZE = 2048;

    private final String roomCode;
    private final Consumer<Announcement> callback;
    private final InetAddress group;
    private final int port;
    private final NetworkInterface networkInterface;
    private final MessageCodec codec;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile boolean running = false;

    private MulticastSocket socket;
    private SocketAddress groupAddress;
    private Thread listenerThread;

    /**
     * Creates an AnnouncementListener using default protocol settings (group 239.10.10.10, port 5002)
     * and the default detected network interface.
     *
     * @param roomCode room filter (or null to receive announcements for all rooms)
     * @param callback callback invoked when a valid announcement arrives
     * @throws IOException if group address resolution fails
     */
    public AnnouncementListener(String roomCode, Consumer<Announcement> callback) throws IOException {
        this(roomCode, callback,
                InetAddress.getByName(ProtocolConstants.MULTICAST_GROUP),
                ProtocolConstants.MULTICAST_PORT,
                MulticastInterfaces.defaultInterface());
    }

    /**
     * Creates an AnnouncementListener with a group address string, port, and network interface.
     *
     * @param roomCode room filter (or null to receive announcements for all rooms)
     * @param callback callback invoked when a valid announcement arrives
     * @param groupAddress IP address string of the multicast group
     * @param port the multicast port to bind
     * @param networkInterface the network interface to join on
     * @throws IOException if group address resolution fails
     */
    public AnnouncementListener(String roomCode, Consumer<Announcement> callback,
                                String groupAddress, int port, NetworkInterface networkInterface) throws IOException {
        this(roomCode, callback,
                InetAddress.getByName(Objects.requireNonNull(groupAddress, "groupAddress must not be null")),
                port, networkInterface);
    }

    /**
     * Creates an AnnouncementListener with full configuration options.
     *
     * @param roomCode room filter (or null to receive announcements for all rooms)
     * @param callback callback invoked when a valid announcement arrives (must not be null)
     * @param group the multicast group InetAddress
     * @param port the UDP port to bind
     * @param networkInterface the network interface to join the group on
     */
    public AnnouncementListener(String roomCode, Consumer<Announcement> callback,
                                InetAddress group, int port, NetworkInterface networkInterface) {
        this.roomCode = roomCode;
        this.callback = Objects.requireNonNull(callback, "callback must not be null");
        this.group = Objects.requireNonNull(group, "group must not be null");
        if (!group.isMulticastAddress()) {
            throw new IllegalArgumentException("Address is not a multicast group: " + group);
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port out of range: " + port);
        }
        this.port = port;
        this.networkInterface = networkInterface != null ? networkInterface : MulticastInterfaces.defaultInterface();
        this.codec = new MessageCodec();
    }

    /**
     * Configures the socket, binds with address reuse enabled, joins the multicast group,
     * and starts the daemon listener thread.
     * <p>
     * Calling {@code start()} more than once is harmless and has no additional effect.
     *
     * @throws IOException if socket creation, binding, or group join fails
     */
    public synchronized void start() throws IOException {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        // Must create unbound and setReuseAddress(true) BEFORE binding so multiple
        // processes or demo windows can listen on the same port on a single machine.
        socket = new MulticastSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));

        groupAddress = new InetSocketAddress(group, port);
        if (networkInterface != null) {
            socket.joinGroup(groupAddress, networkInterface);
        } else {
            NetworkInterface fallback = MulticastInterfaces.defaultInterface();
            if (fallback != null) {
                socket.joinGroup(groupAddress, fallback);
            }
        }

        running = true;
        listenerThread = new Thread(this::receiveLoop, "AnnouncementListener-" + port);
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void receiveLoop() {
        byte[] buffer = new byte[BUFFER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running && !socket.isClosed()) {
            try {
                socket.receive(packet);
                String line = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                handleDatagram(line);
            } catch (SocketException e) {
                if (!running || socket.isClosed()) {
                    break;
                }
                LOGGER.log(Level.WARNING, "Socket error in multicast receive loop", e);
            } catch (Exception e) {
                if (!running || socket.isClosed()) {
                    break;
                }
                LOGGER.log(Level.WARNING, "Error processing incoming multicast datagram", e);
            }
        }
    }

    private void handleDatagram(String line) {
        try {
            Message message = codec.decode(line);
            if (message.type() != MessageType.ANNOUNCEMENT) {
                return;
            }

            JsonObject payload = message.payload();
            if (payload == null || !payload.has("roomCode") || !payload.has("text")) {
                return;
            }

            String targetRoom = payload.get("roomCode").getAsString();
            String text = payload.get("text").getAsString();

            Announcement announcement = new Announcement(targetRoom, text);

            // Filter by room if a specific roomCode filter was set
            if (roomCode != null && !roomCode.equals(announcement.roomCode())) {
                return;
            }

            callback.accept(announcement);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Ignored malformed or invalid announcement datagram: " + e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        running = false;

        if (socket != null && !socket.isClosed()) {
            try {
                if (groupAddress != null && networkInterface != null) {
                    socket.leaveGroup(groupAddress, networkInterface);
                }
            } catch (Exception ignored) {
            }
            socket.close();
        }

        if (listenerThread != null && listenerThread.isAlive()) {
            listenerThread.interrupt();
        }
    }

    public boolean isRunning() {
        return running && socket != null && !socket.isClosed();
    }
}
