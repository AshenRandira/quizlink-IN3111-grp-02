package lk.quizlink.server;

import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageCodec;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The central QuizLink server, built with Java NIO.
 *
 * Big idea: ONE thread (the "Selector thread") serves ALL clients.
 * Instead of one blocked thread per client, we ask a Selector "which channels are ready?"
 * and only touch the ones that are:
 *
 *   OP_ACCEPT  - a new client wants to connect
 *   OP_READ    - a client sent bytes
 *   OP_WRITE   - a client's socket can take more bytes (only asked for while we have data waiting)
 *
 * All channels are NON-BLOCKING, so no call here ever waits for the network.
 *
 * Lifecycle:  new NioServer(...)  ->  open() binds the port  ->  run() loops until stop().
 */
public final class NioServer implements MessageSender {

    /** Default limit for one message (one line). Bigger than any real quiz, small enough to be safe. */
    public static final int DEFAULT_MAX_FRAME_BYTES = 256 * 1024;

    private static final int READ_BUFFER_BYTES = 4096;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final int requestedPort;
    private final MessageRouter router;
    private final int maxFrameBytes;
    private final MessageCodec codec = new MessageCodec();

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private volatile boolean running = false;

    /** One shared buffer for reading, safe because only the Selector thread reads. */
    private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_BYTES);

    private final Map<String, ClientConnection> connections = new ConcurrentHashMap<>();
    private final AtomicInteger nextConnectionNumber = new AtomicInteger(1);

    /** Connections that have new data to write. Other threads add here, then wake the Selector. */
    private final Queue<ClientConnection> writeRequests = new ConcurrentLinkedQueue<>();

    public NioServer(int port, MessageRouter router) {
        this(port, router, DEFAULT_MAX_FRAME_BYTES);
    }

    public NioServer(int port, MessageRouter router, int maxFrameBytes) {
        this.requestedPort = port;
        this.router = router;
        this.maxFrameBytes = maxFrameBytes;
    }

    // ---------------------------------------------------------------- startup / shutdown

    /** Step 1: open the Selector and the listening channel, and bind the port. */
    public void open() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false); // required before registering with a Selector
        serverChannel.bind(new InetSocketAddress(requestedPort));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        log("Server listening on TCP port " + getPort());
    }

    /** The actual port (useful when 0 was requested, which means "any free port"). */
    public int getPort() {
        try {
            return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        } catch (IOException e) {
            return requestedPort;
        }
    }

    public int getConnectionCount() {
        return connections.size();
    }

    /** Ask the loop to finish. Safe to call from any thread. */
    public void stop() {
        running = false;
        if (selector != null) {
            selector.wakeup(); // select() is blocking; wake it so it can notice running == false
        }
    }

    // ---------------------------------------------------------------- the event loop

    /** Step 2: the Selector loop. Blocks the calling thread until stop() is called. */
    public void run() {
        running = true;
        try {
            while (running) {
                selector.select(); // sleeps until a channel is ready or wakeup() is called
                registerPendingWrites();

                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove(); // we must remove handled keys ourselves
                    handleKey(key);
                }
            }
        } catch (IOException e) {
            log("Selector failed, server stopping: " + e);
        } finally {
            shutdown();
        }
    }

    /** Handles one ready key. A failure here only affects that one client. */
    private void handleKey(SelectionKey key) {
        try {
            if (!key.isValid()) {
                return;
            }
            if (key.isAcceptable()) {
                acceptClient();
            }
            if (key.isValid() && key.isReadable()) {
                readFromClient((ClientConnection) key.attachment());
            }
            if (key.isValid() && key.isWritable()) {
                writeToClient((ClientConnection) key.attachment());
            }
        } catch (CancelledKeyException e) {
            // The client was closed while we were handling it. Nothing to do.
        } catch (IOException | RuntimeException e) {
            ClientConnection connection = (ClientConnection) key.attachment();
            if (connection != null) {
                closeConnection(connection, "error: " + e.getMessage());
            } else {
                log("Accept failed: " + e); // the listening socket itself keeps running
            }
        }
    }

    // ---------------------------------------------------------------- accept

    private void acceptClient() throws IOException {
        SocketChannel channel = serverChannel.accept();
        if (channel == null) {
            return; // another event already took it
        }
        channel.configureBlocking(false);

        ClientConnection connection =
                new ClientConnection("conn-" + nextConnectionNumber.getAndIncrement(), channel);
        SelectionKey key = channel.register(selector, SelectionKey.OP_READ, connection);
        connection.setKey(key);
        connections.put(connection.getId(), connection);
        log("CONNECT    " + connection + "  (clients connected: " + connections.size() + ")");
    }

    // ---------------------------------------------------------------- read

    private void readFromClient(ClientConnection connection) throws IOException {
        readBuffer.clear();
        int bytesRead = connection.getChannel().read(readBuffer);

        if (bytesRead == -1) { // the client closed its side
            closeConnection(connection, "client disconnected");
            return;
        }
        if (bytesRead == 0 || connection.isCloseAfterWrite()) {
            return; // nothing new, or we are already closing this client: ignore its data
        }

        readBuffer.flip(); // switch the buffer from "filling" to "reading"
        List<String> frames;
        try {
            frames = connection.extractFrames(readBuffer, maxFrameBytes);
        } catch (ClientConnection.FrameTooLargeException e) {
            log("OVERSIZED  " + connection + ": " + e.getMessage());
            sendFinalError(connection, "INVALID_MESSAGE", "Message too large");
            return;
        }

        for (String frame : frames) {
            if (frame.isBlank()) {
                continue; // ignore empty lines
            }
            handleFrame(connection, frame);
        }
    }

    private void handleFrame(ClientConnection connection, String frame) {
        Message message;
        try {
            message = codec.decode(frame);
        } catch (IllegalArgumentException e) {
            log("MALFORMED  " + connection + ": " + e.getMessage());
            send(connection, MessageRouter.errorReply("", "INVALID_MESSAGE", "Malformed message"));
            return; // keep the connection: one bad line should not punish the client
        }
        log("RECEIVED   " + connection + "  " + message.type() + "  requestId=" + message.requestId());
        router.route(connection, message, this);
    }

    // ---------------------------------------------------------------- write

    /**
     * Queue a message for a client. Thread-safe: workers may call this.
     * We do not write directly; we queue the bytes, then ask the Selector thread to write them.
     */
    @Override
    public void send(ClientConnection connection, Message message) {
        if (connection.isClosed()) {
            return;
        }
        byte[] bytes = codec.encode(message).getBytes(StandardCharsets.UTF_8);
        connection.queueOutbound(ByteBuffer.wrap(bytes));
        writeRequests.add(connection);
        selector.wakeup();
    }

    /** Send an ERROR and then close the connection once the error has been written. */
    private void sendFinalError(ClientConnection connection, String code, String text) {
        connection.markCloseAfterWrite();
        stopReading(connection);
        send(connection, MessageRouter.errorReply("", code, text));
    }

    private void stopReading(ClientConnection connection) {
        SelectionKey key = connection.getKey();
        if (key != null && key.isValid()) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
        }
    }

    /** Runs on the Selector thread: turn on OP_WRITE for connections that have data waiting. */
    private void registerPendingWrites() {
        ClientConnection connection;
        while ((connection = writeRequests.poll()) != null) {
            SelectionKey key = connection.getKey();
            if (connection.isClosed() || key == null || !key.isValid()) {
                continue;
            }
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        }
    }

    /** The socket is ready for writing: send as much as it will take. */
    private void writeToClient(ClientConnection connection) throws IOException {
        Queue<ByteBuffer> queue = connection.outboundQueue();
        ByteBuffer next;
        while ((next = queue.peek()) != null) {
            connection.getChannel().write(next);
            if (next.hasRemaining()) {
                return; // partial write: keep it at the head and wait for the next OP_WRITE
            }
            queue.poll(); // fully sent
        }

        // Queue is empty: stop asking for OP_WRITE, otherwise the Selector would spin.
        SelectionKey key = connection.getKey();
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);

        if (connection.isCloseAfterWrite()) {
            closeConnection(connection, "closed after final message");
        }
    }

    // ---------------------------------------------------------------- close

    private void closeConnection(ClientConnection connection, String reason) {
        if (connection.isClosed()) {
            return; // already closed (can be triggered twice)
        }
        connection.markClosed();
        connections.remove(connection.getId());

        SelectionKey key = connection.getKey();
        if (key != null) {
            key.cancel();
        }
        try {
            connection.getChannel().close();
        } catch (IOException ignored) {
            // nothing more we can do
        }
        log("DISCONNECT " + connection + "  (" + reason + ")  (clients connected: " + connections.size() + ")");
        router.connectionClosed(connection);
    }

    private void shutdown() {
        running = false;
        for (ClientConnection connection : new ArrayList<>(connections.values())) {
            closeConnection(connection, "server shutting down");
        }
        try {
            serverChannel.close();
            selector.close();
        } catch (IOException ignored) {
            // shutting down anyway
        }
        log("Server stopped");
    }

    private static void log(String text) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] " + text);
    }
}
