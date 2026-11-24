package lk.quizlink.host;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lk.quizlink.common.model.Quiz;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageCodec;
import lk.quizlink.common.protocol.MessageType;
import lk.quizlink.common.protocol.ProtocolConstants;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Socket-based TCP implementation of {@link HostGateway} communicating with the QuizLink server.
 * <p>
 * Performs line-delimited JSON framing using {@link MessageCodec}, coordinates request-response
 * correlation via unique {@code requestId} headers, schedules individual request timeouts,
 * routes unsolicited server events, and handles connection teardown gracefully.
 */
public class TcpHostGateway implements HostGateway {

    private static final Logger LOGGER = Logger.getLogger(TcpHostGateway.class.getName());
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 3000;
    private static final long DEFAULT_REQUEST_TIMEOUT_MS = 5000;

    private final Socket socket;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final Object writeLock = new Object();

    private final MessageCodec codec = new MessageCodec();
    private final Gson gson = new Gson();
    private final AtomicLong requestCounter = new AtomicLong(0);
    private final ConcurrentHashMap<String, CompletableFuture<Message>> pendingRequests = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;
    private final long requestTimeoutMs;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean disconnectListenerFired = new AtomicBoolean(false);

    private volatile Consumer<Message> eventListener;
    private volatile Runnable disconnectListener;
    private final Thread readerThread;

    /**
     * Connects to the QuizLink server at the default port (5000) with default timeouts.
     *
     * @param host server hostname or IP address
     * @throws IOException if connection fails
     */
    public TcpHostGateway(String host) throws IOException {
        this(host, ProtocolConstants.TCP_PORT);
    }

    /**
     * Connects to the QuizLink server at the specified host and port with default timeouts.
     *
     * @param host server hostname or IP address
     * @param port server TCP port
     * @throws IOException if connection fails
     */
    public TcpHostGateway(String host, int port) throws IOException {
        this(host, port, DEFAULT_REQUEST_TIMEOUT_MS, DEFAULT_CONNECT_TIMEOUT_MS);
    }

    /**
     * Package-private constructor allowing tests to specify custom request and connect timeouts.
     *
     * @param host server hostname or IP
     * @param port server port
     * @param requestTimeoutMs timeout in milliseconds for server replies
     * @param connectTimeoutMs timeout in milliseconds for socket connection
     * @throws IOException if socket connection fails
     */
    TcpHostGateway(String host, int port, long requestTimeoutMs, int connectTimeoutMs) throws IOException {
        Objects.requireNonNull(host, "host must not be null");
        this.requestTimeoutMs = requestTimeoutMs;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TcpHostGateway-Scheduler");
            t.setDaemon(true);
            return t;
        });

        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);

        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

        this.readerThread = new Thread(this::readLoop, "TcpHostGateway-Reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    public static TcpHostGateway connect(String host) throws IOException {
        return new TcpHostGateway(host);
    }

    public static TcpHostGateway connect(String host, int port) throws IOException {
        return new TcpHostGateway(host, port);
    }

    @Override
    public CompletableFuture<String> createRoom(Quiz quiz) {
        if (quiz == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("quiz must not be null"));
        }

        JsonObject payload = new JsonObject();
        payload.add("quiz", gson.toJsonTree(quiz));

        return sendRequest(MessageType.CREATE_ROOM, payload).thenApply(reply -> {
            JsonObject replyPayload = reply.payload();
            if (replyPayload == null || !replyPayload.has("roomCode")) {
                throw new HostException("INVALID_RESPONSE", "Server reply missing roomCode");
            }
            return replyPayload.get("roomCode").getAsString();
        });
    }

    @Override
    public CompletableFuture<Void> startQuiz(String roomCode) {
        validateRoomCode(roomCode);

        JsonObject payload = new JsonObject();
        payload.addProperty("roomCode", roomCode);

        return sendRequest(MessageType.START_QUIZ, payload).thenAccept(reply -> {
            // Completed without ERROR
        });
    }

    @Override
    public CompletableFuture<Void> endQuiz(String roomCode) {
        validateRoomCode(roomCode);

        JsonObject payload = new JsonObject();
        payload.addProperty("roomCode", roomCode);

        return sendRequest(MessageType.END_QUIZ, payload).thenAccept(reply -> {
            // Completed without ERROR
        });
    }

    @Override
    public void setEventListener(Consumer<Message> listener) {
        this.eventListener = listener;
    }

    @Override
    public void setDisconnectListener(Runnable listener) {
        this.disconnectListener = listener;
    }

    private void validateRoomCode(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            throw new IllegalArgumentException("roomCode is required and must not be blank");
        }
    }

    private CompletableFuture<Message> sendRequest(MessageType type, JsonObject payload) {
        if (closed.get() || socket.isClosed()) {
            return CompletableFuture.failedFuture(new HostException("NOT_CONNECTED", "Gateway is closed"));
        }

        String requestId = "host-" + requestCounter.incrementAndGet();
        Message message = Message.of(type, requestId, payload);
        String line = codec.encode(message);

        CompletableFuture<Message> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            CompletableFuture<Message> timedOut = pendingRequests.remove(requestId);
            if (timedOut != null) {
                timedOut.completeExceptionally(
                        new HostException("TIMEOUT", "Request " + requestId + " (" + type + ") timed out after " + requestTimeoutMs + "ms"));
            }
        }, requestTimeoutMs, TimeUnit.MILLISECONDS);

        future.whenComplete((res, ex) -> timeoutTask.cancel(false));

        try {
            synchronized (writeLock) {
                if (closed.get() || socket.isClosed()) {
                    throw new HostException("NOT_CONNECTED", "Gateway is closed");
                }
                writer.write(line);
                writer.flush();
            }
        } catch (IOException e) {
            pendingRequests.remove(requestId);
            timeoutTask.cancel(false);
            future.completeExceptionally(new HostException("SEND_FAILED", "Failed to send request: " + e.getMessage(), e));
            handleDisconnect(true);
        }

        return future;
    }

    private void readLoop() {
        try {
            String line;
            while (!closed.get() && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                handleIncomingLine(line);
            }
        } catch (SocketException e) {
            // Normal when socket is closed during disconnect
        } catch (IOException e) {
            if (!closed.get()) {
                LOGGER.log(Level.WARNING, "I/O error in host reader loop", e);
            }
        } finally {
            handleDisconnect(true);
        }
    }

    private void handleIncomingLine(String line) {
        try {
            Message message = codec.decode(line);
            String reqId = message.requestId();

            if (reqId == null || reqId.isEmpty()) {
                // Unsolicited server event
                Consumer<Message> listener = this.eventListener;
                if (listener != null) {
                    try {
                        listener.accept(message);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error in event listener callback", e);
                    }
                }
                return;
            }

            CompletableFuture<Message> pending = pendingRequests.remove(reqId);
            if (pending != null) {
                if (message.type() == MessageType.ERROR) {
                    JsonObject payload = message.payload();
                    String code = payload != null && payload.has("code") ? payload.get("code").getAsString() : "ERROR";
                    String messageText = payload != null && payload.has("message") ? payload.get("message").getAsString() : "Server error";
                    pending.completeExceptionally(new HostException(code, messageText));
                } else {
                    pending.complete(message);
                }
            } else {
                LOGGER.log(Level.FINE, "Received reply for unknown or already completed request: {0}", reqId);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Skipping malformed or unrecognized line from server: " + e.getMessage());
        }
    }

    private void handleDisconnect(boolean unexpected) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        closeResources();

        HostException disconnectException = new HostException("DISCONNECTED", "Connection to server was lost");
        for (CompletableFuture<Message> pending : pendingRequests.values()) {
            pending.completeExceptionally(disconnectException);
        }
        pendingRequests.clear();

        if (unexpected && disconnectListenerFired.compareAndSet(false, true)) {
            Runnable listener = this.disconnectListener;
            if (listener != null) {
                try {
                    listener.run();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error in disconnect listener callback", e);
                }
            }
        }
    }

    private void closeResources() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        scheduler.shutdownNow();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeResources();
        HostException closeException = new HostException("CLOSED", "Host gateway was closed");
        for (CompletableFuture<Message> pending : pendingRequests.values()) {
            pending.completeExceptionally(closeException);
        }
        pendingRequests.clear();
    }

    public boolean isClosed() {
        return closed.get() || socket.isClosed();
    }
}
