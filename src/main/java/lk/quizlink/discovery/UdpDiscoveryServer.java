package lk.quizlink.discovery;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lk.quizlink.common.protocol.ProtocolConstants;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Receives QuizLink UDP discovery requests and unicasts the TCP endpoint to each requester.
 * <p>
 * The server binds a wildcard local address so clients on the same computer or LAN can reach
 * it. One dedicated background thread blocks in {@link DatagramSocket#receive(DatagramPacket)};
 * it does not occupy a caller thread or a future NIO Selector thread.
 * <p>
 * A valid packet must contain exactly {@link ProtocolConstants#DISCOVERY_REQUEST} encoded as
 * UTF-8. Empty, malformed, unrelated, unsupported, or oversized packets are ignored without
 * a response. Each valid request receives a JSON response at the packet's source address and
 * UDP source port.
 * <p>
 * Calling {@link #start()} while running and calling {@link #stop()} while stopped are
 * harmless. An instance may be restarted after {@code stop()} returns.
 */
public final class UdpDiscoveryServer implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(UdpDiscoveryServer.class.getName());
    private static final Gson GSON = new Gson();
    private static final int RECEIVE_BUFFER_SIZE = 512;

    private final Object lifecycleLock = new Object();
    private final int listeningPort;
    private final byte[] requestBytes =
            ProtocolConstants.DISCOVERY_REQUEST.getBytes(StandardCharsets.UTF_8);
    private final byte[] responseBytes;

    private volatile boolean running;
    private DatagramSocket socket;
    private Thread listenerThread;
    private int boundPort = -1;

    /**
     * Creates a discovery server using the project's configured UDP and TCP ports.
     */
    public UdpDiscoveryServer() {
        this(ProtocolConstants.DISCOVERY_PORT, ProtocolConstants.TCP_PORT);
    }

    /**
     * Creates a discovery server with configurable ports, primarily for isolated tests.
     *
     * @param listeningPort UDP port to bind; zero requests an ephemeral port
     * @param advertisedTcpPort TCP port included in the discovery response
     * @throws IllegalArgumentException if either port is outside its valid range
     */
    public UdpDiscoveryServer(int listeningPort, int advertisedTcpPort) {
        if (listeningPort < 0 || listeningPort > 65_535) {
            throw new IllegalArgumentException("UDP listening port must be between 0 and 65535");
        }
        if (advertisedTcpPort < 1 || advertisedTcpPort > 65_535) {
            throw new IllegalArgumentException("Advertised TCP port must be between 1 and 65535");
        }
        this.listeningPort = listeningPort;

        JsonObject response = new JsonObject();
        response.addProperty("service", "QuizLink");
        response.addProperty("version", ProtocolConstants.VERSION);
        response.addProperty("tcpPort", advertisedTcpPort);
        this.responseBytes = GSON.toJson(response).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Binds the UDP socket and starts the background listener.
     * <p>
     * Binding happens on the calling thread, so an unavailable port is reported immediately.
     * Repeated calls while already running do nothing.
     *
     * @throws IOException if the UDP socket cannot be created or bound
     */
    public void start() throws IOException {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            if (listenerThread != null && listenerThread.isAlive()) {
                throw new IllegalStateException("The previous discovery listener is still stopping");
            }

            DatagramSocket candidate = new DatagramSocket(null);
            try {
                candidate.bind(new InetSocketAddress(listeningPort));
            } catch (IOException | RuntimeException e) {
                candidate.close();
                throw e;
            }

            socket = candidate;
            boundPort = candidate.getLocalPort();
            running = true;
            listenerThread = new Thread(() -> receiveLoop(candidate),
                    "quizlink-udp-discovery-" + boundPort);
            listenerThread.setDaemon(true);
            listenerThread.start();
        }
    }

    /**
     * Stops the listener, closes its socket and waits for the receive thread to finish.
     * <p>
     * This method is idempotent. If the calling thread is interrupted while waiting, shutdown
     * still completes and the interrupt status is restored before returning.
     */
    public void stop() {
        Thread threadToJoin;
        synchronized (lifecycleLock) {
            running = false;
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            threadToJoin = listenerThread;
        }

        if (threadToJoin == null || threadToJoin == Thread.currentThread()) {
            return;
        }

        boolean interrupted = false;
        while (threadToJoin.isAlive()) {
            try {
                threadToJoin.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }

        synchronized (lifecycleLock) {
            if (listenerThread == threadToJoin) {
                listenerThread = null;
                socket = null;
                boundPort = -1;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns whether the listener is currently accepting discovery requests.
     *
     * @return {@code true} while the bound listener is active
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the actual UDP port selected by the operating system.
     *
     * @return the bound UDP port, including the ephemeral port when configured with zero
     * @throws IllegalStateException if the service is not currently running
     */
    public int getBoundPort() {
        synchronized (lifecycleLock) {
            if (!running || socket == null || socket.isClosed()) {
                throw new IllegalStateException("Discovery server is not running");
            }
            return boundPort;
        }
    }

    /**
     * Stops the service and releases its socket.
     */
    @Override
    public void close() {
        stop();
    }

    private void receiveLoop(DatagramSocket listenerSocket) {
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        DatagramPacket request = new DatagramPacket(buffer, buffer.length);

        try {
            while (running && !listenerSocket.isClosed()) {
                request.setLength(buffer.length);
                listenerSocket.receive(request);
                if (!isValidRequest(request)) {
                    continue;
                }

                DatagramPacket response = new DatagramPacket(responseBytes, responseBytes.length,
                        request.getAddress(), request.getPort());
                listenerSocket.send(response);
            }
        } catch (SocketException e) {
            if (running && !listenerSocket.isClosed()) {
                LOGGER.log(Level.SEVERE, "UDP discovery socket failed unexpectedly", e);
            }
        } catch (IOException e) {
            if (running && !listenerSocket.isClosed()) {
                LOGGER.log(Level.SEVERE, "UDP discovery listener stopped after an I/O failure", e);
            }
        } finally {
            listenerSocket.close();
            synchronized (lifecycleLock) {
                if (socket == listenerSocket) {
                    running = false;
                }
            }
        }
    }

    private boolean isValidRequest(DatagramPacket packet) {
        if (packet.getLength() != requestBytes.length) {
            return false;
        }
        String request = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                StandardCharsets.UTF_8);
        return ProtocolConstants.DISCOVERY_REQUEST.equals(request);
    }
}
