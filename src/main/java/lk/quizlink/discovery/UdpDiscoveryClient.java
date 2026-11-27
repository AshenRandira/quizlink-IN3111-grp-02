package lk.quizlink.discovery;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.Strictness;
import lk.quizlink.common.protocol.ProtocolConstants;

import java.io.IOException;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends UDP discovery requests and returns the first valid QuizLink server response.
 * <p>
 * The default configuration broadcasts to active IPv4 interface broadcast addresses and the
 * limited broadcast address. A configurable unicast destination is also available for
 * deterministic local tests. Every discovery call owns and closes its own ephemeral
 * {@link DatagramSocket}, so independent calls do not share mutable network state.
 * <p>
 * {@link #discover()} blocks until a valid response arrives or the overall timeout expires.
 * Do not call it on Swing's Event Dispatch Thread; run it on a background executor and dispatch
 * resulting UI changes back to the EDT. Discovery only identifies an advertised endpoint; it
 * does not connect to TCP or authenticate the responding server.
 */
public final class UdpDiscoveryClient {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(1_500);

    private static final Logger LOGGER = Logger.getLogger(UdpDiscoveryClient.class.getName());
    private static final int MAX_RESPONSE_BYTES = 4_096;
    private static final byte[] REQUEST_BYTES =
            ProtocolConstants.DISCOVERY_REQUEST.getBytes(StandardCharsets.UTF_8);
    private static final InetAddress LIMITED_BROADCAST = limitedBroadcastAddress();

    private final InetAddress unicastDestination;
    private final int discoveryPort;
    private final long timeoutNanos;

    /**
     * Creates a client that broadcasts using the project's default UDP port and timeout.
     */
    public UdpDiscoveryClient() {
        this(ProtocolConstants.DISCOVERY_PORT, DEFAULT_TIMEOUT);
    }

    /**
     * Creates a broadcasting client with a configurable discovery port and overall timeout.
     *
     * @param discoveryPort destination UDP port
     * @param timeout overall time allowed for discovery
     * @throws IllegalArgumentException if the port or timeout is invalid
     */
    public UdpDiscoveryClient(int discoveryPort, Duration timeout) {
        this(null, discoveryPort, timeout, true);
    }

    /**
     * Creates a client that sends to one configured unicast destination.
     * <p>
     * This configuration is useful for loopback tests and controlled networks. It is not LAN
     * broadcast discovery.
     *
     * @param destination unicast server address to probe
     * @param discoveryPort destination UDP port
     * @param timeout overall time allowed for discovery
     * @throws IllegalArgumentException if the destination, port, or timeout is invalid
     */
    public UdpDiscoveryClient(InetAddress destination, int discoveryPort, Duration timeout) {
        this(destination, discoveryPort, timeout, false);
    }

    private UdpDiscoveryClient(InetAddress destination, int discoveryPort, Duration timeout,
                              boolean broadcast) {
        if (!broadcast && destination == null) {
            throw new IllegalArgumentException("Unicast destination is required");
        }
        if (destination != null
                && (destination.isAnyLocalAddress()
                || destination.isMulticastAddress()
                || isLimitedBroadcast(destination))) {
            throw new IllegalArgumentException("Destination must be a unicast address");
        }
        if (discoveryPort < 1 || discoveryPort > 65_535) {
            throw new IllegalArgumentException("Discovery port must be between 1 and 65535");
        }
        this.timeoutNanos = validateTimeout(timeout);
        this.unicastDestination = destination;
        this.discoveryPort = discoveryPort;
    }

    /**
     * Discovers a QuizLink server and returns its advertised TCP endpoint.
     * <p>
     * The request is sent using exact UTF-8 protocol bytes. Invalid, malformed, unsupported,
     * truncated, or oversized responses are ignored until the original overall deadline. When
     * multiple servers respond, the first valid response received is returned.
     *
     * @return the first valid server response, or {@link Optional#empty()} when the timeout
     * expires without one
     * @throws IOException if the UDP socket cannot be configured or all request sends fail
     */
    public Optional<DiscoveryResult> discover() throws IOException {
        long deadline = System.nanoTime() + timeoutNanos;
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.bind(new InetSocketAddress(0));
            socket.setBroadcast(true);

            List<InetAddress> destinations = unicastDestination == null
                    ? broadcastDestinations()
                    : List.of(unicastDestination);
            sendRequests(socket, destinations);

            byte[] responseBuffer = new byte[MAX_RESPONSE_BYTES + 1];
            DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
            while (true) {
                int remainingMillis = remainingTimeoutMillis(deadline);
                if (remainingMillis <= 0) {
                    return Optional.empty();
                }
                socket.setSoTimeout(remainingMillis);
                try {
                    response.setLength(responseBuffer.length);
                    socket.receive(response);
                } catch (java.net.SocketTimeoutException timeoutException) {
                    if (System.nanoTime() >= deadline) {
                        return Optional.empty();
                    }
                    continue;
                }

                if (response.getLength() > MAX_RESPONSE_BYTES) {
                    continue;
                }
                Optional<DiscoveryResult> result = parseResponse(response);
                if (result.isPresent()) {
                    return result;
                }
                if (System.nanoTime() >= deadline) {
                    return Optional.empty();
                }
            }
        }
    }

    private void sendRequests(DatagramSocket socket, List<InetAddress> destinations) throws IOException {
        IOException sendFailure = null;
        int successfulSends = 0;
        for (InetAddress destination : destinations) {
            try {
                socket.send(new DatagramPacket(REQUEST_BYTES, REQUEST_BYTES.length,
                        destination, discoveryPort));
                successfulSends++;
            } catch (IOException exception) {
                if (sendFailure == null) {
                    sendFailure = new IOException("Unable to send UDP discovery request");
                }
                sendFailure.addSuppressed(exception);
                LOGGER.log(Level.FINE, "Could not send discovery request to " + destination, exception);
            }
        }
        if (successfulSends == 0) {
            if (sendFailure != null) {
                throw sendFailure;
            }
            throw new IOException("No UDP discovery destinations are available");
        }
    }

    private static List<InetAddress> broadcastDestinations() {
        LinkedHashSet<InetAddress> destinations = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    addInterfaceBroadcasts(destinations, interfaces.nextElement());
                }
            }
        } catch (SocketException exception) {
            LOGGER.log(Level.FINE, "Could not enumerate network interfaces; using limited broadcast",
                    exception);
        }
        destinations.add(LIMITED_BROADCAST);
        return new ArrayList<>(destinations);
    }

    private static void addInterfaceBroadcasts(LinkedHashSet<InetAddress> destinations,
                                               NetworkInterface networkInterface) {
        try {
            if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                return;
            }
            networkInterface.getInterfaceAddresses().stream()
                    .filter(address -> address.getAddress() instanceof Inet4Address)
                    .map(java.net.InterfaceAddress::getBroadcast)
                    .filter(address -> address != null)
                    .forEach(destinations::add);
        } catch (SocketException exception) {
            LOGGER.log(Level.FINE, "Ignoring unavailable network interface " + networkInterface.getName(),
                    exception);
        }
    }

    private static Optional<DiscoveryResult> parseResponse(DatagramPacket packet) {
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength()))
                    .toString();

            JsonElement root;
            try (JsonReader reader = new JsonReader(new StringReader(json))) {
                reader.setStrictness(Strictness.STRICT);
                root = JsonParser.parseReader(reader);
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    return Optional.empty();
                }
            }
            if (!root.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject response = root.getAsJsonObject();
            String service = stringField(response, "service");
            Integer version = integerField(response, "version");
            Integer tcpPort = integerField(response, "tcpPort");
            if (!"QuizLink".equals(service)
                    || version == null
                    || version != ProtocolConstants.VERSION
                    || tcpPort == null
                    || tcpPort < 1
                    || tcpPort > 65_535) {
                return Optional.empty();
            }
            return Optional.of(new DiscoveryResult(packet.getAddress(), tcpPort));
        } catch (IOException | JsonParseException | IllegalStateException exception) {
            return Optional.empty();
        }
    }

    private static String stringField(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : null;
    }

    private static Integer integerField(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            return null;
        }
        String number = primitive.getAsString();
        if (!number.matches("-?(0|[1-9][0-9]*)")) {
            return null;
        }
        try {
            return Integer.valueOf(number);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static long validateTimeout(Duration timeout) {
        if (timeout == null) {
            throw new IllegalArgumentException("Timeout is required");
        }
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Timeout is too large", exception);
        }
        if (timeoutNanos < 1_000_000L || timeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Timeout must be between 1 millisecond and "
                    + Integer.MAX_VALUE + " milliseconds");
        }
        return timeoutNanos;
    }

    private static int remainingTimeoutMillis(long deadline) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            return 0;
        }
        long roundedUpMillis = (remainingNanos + 999_999L) / 1_000_000L;
        return (int) Math.min(roundedUpMillis, Integer.MAX_VALUE);
    }

    private static boolean isLimitedBroadcast(InetAddress address) {
        return address instanceof Inet4Address
                && address.getAddress()[0] == (byte) 255
                && address.getAddress()[1] == (byte) 255
                && address.getAddress()[2] == (byte) 255
                && address.getAddress()[3] == (byte) 255;
    }

    private static InetAddress limitedBroadcastAddress() {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255});
        } catch (java.net.UnknownHostException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
