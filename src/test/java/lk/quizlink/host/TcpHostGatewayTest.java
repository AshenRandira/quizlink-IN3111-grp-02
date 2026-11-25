package lk.quizlink.host;

import com.google.gson.JsonObject;
import lk.quizlink.common.model.Question;
import lk.quizlink.common.model.Quiz;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageCodec;
import lk.quizlink.common.protocol.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpHostGatewayTest {

    private static final MessageCodec CODEC = new MessageCodec();

    private ServerSocket serverSocket;
    private int port;
    private Socket clientSocket;
    private BufferedReader serverIn;
    private BufferedWriter serverOut;

    private static Quiz sampleQuiz() {
        Question q = new Question("q1", "What is UDP?", List.of("Datagram", "Stream"), 0, 15, 10);
        return new Quiz("quiz-net", "Networking Basics", List.of(q));
    }

    @BeforeEach
    void setUpServer() throws IOException {
        serverSocket = new ServerSocket(0);
        port = serverSocket.getLocalPort();
    }

    @AfterEach
    void tearDownServer() throws IOException {
        if (clientSocket != null) clientSocket.close();
        if (serverSocket != null) serverSocket.close();
    }

    private void acceptClient() throws IOException {
        clientSocket = serverSocket.accept();
        serverIn = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
        serverOut = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8));
    }

    private void sendToClient(Message message) throws IOException {
        serverOut.write(CODEC.encode(message));
        serverOut.flush();
    }

    private void sendRawToClient(String rawLine) throws IOException {
        serverOut.write(rawLine.endsWith("\n") ? rawLine : rawLine + "\n");
        serverOut.flush();
    }

    @Test
    void createRoomGetsRoomCodeAndSendsFullQuiz() throws Exception {
        CompletableFuture<Void> serverTask = CompletableFuture.runAsync(() -> {
            try {
                acceptClient();
                String line = serverIn.readLine();
                Message request = CODEC.decode(line);

                assertEquals(MessageType.CREATE_ROOM, request.type());
                JsonObject payload = request.payload();
                assertNotNull(payload.get("quiz"));
                assertEquals("quiz-net", payload.getAsJsonObject("quiz").get("id").getAsString());

                // Send reply
                JsonObject replyPayload = new JsonObject();
                replyPayload.addProperty("roomCode", "RM1234");
                Message reply = Message.of(MessageType.ROOM_CREATED, request.requestId(), replyPayload);
                sendToClient(reply);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try (TcpHostGateway gateway = new TcpHostGateway("127.0.0.1", port)) {
            CompletableFuture<String> roomCodeFuture = gateway.createRoom(sampleQuiz());
            String roomCode = roomCodeFuture.get(3, TimeUnit.SECONDS);
            assertEquals("RM1234", roomCode);
            serverTask.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void errorReplyFailsFutureWithRightCode() throws Exception {
        CompletableFuture<Void> serverTask = CompletableFuture.runAsync(() -> {
            try {
                acceptClient();
                String line = serverIn.readLine();
                Message request = CODEC.decode(line);

                JsonObject errorPayload = new JsonObject();
                errorPayload.addProperty("code", "INVALID_ROOM");
                errorPayload.addProperty("message", "Room does not exist");
                Message errorReply = Message.of(MessageType.ERROR, request.requestId(), errorPayload);
                sendToClient(errorReply);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try (TcpHostGateway gateway = new TcpHostGateway("127.0.0.1", port)) {
            CompletableFuture<Void> startFuture = gateway.startQuiz("NON_EXISTENT");
            ExecutionException exception = assertThrows(ExecutionException.class, () -> startFuture.get(3, TimeUnit.SECONDS));

            assertInstanceOf(HostException.class, exception.getCause());
            HostException hostException = (HostException) exception.getCause();
            assertEquals("INVALID_ROOM", hostException.getCode());
            assertEquals("Room does not exist", hostException.getMessage());
            serverTask.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void noReplyLeadsToTimeoutFailure() throws Exception {
        CompletableFuture<Void> serverTask = CompletableFuture.runAsync(() -> {
            try {
                acceptClient();
                // Read request but intentionally do NOT reply
                serverIn.readLine();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Use short 300ms timeout for fast testing
        try (TcpHostGateway gateway = new TcpHostGateway("127.0.0.1", port, 300, 2000)) {
            CompletableFuture<Void> endFuture = gateway.endQuiz("TEST_ROOM");
            ExecutionException exception = assertThrows(ExecutionException.class, () -> endFuture.get(2, TimeUnit.SECONDS));

            assertInstanceOf(HostException.class, exception.getCause());
            HostException hostException = (HostException) exception.getCause();
            assertEquals("TIMEOUT", hostException.getCode());
            serverTask.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void unsolicitedEventReachesEventListener() throws Exception {
        CountDownLatch eventLatch = new CountDownLatch(1);
        CountDownLatch listenerRegistered = new CountDownLatch(1);
        AtomicReference<Message> receivedEvent = new AtomicReference<>();

        CompletableFuture<Void> serverTask = CompletableFuture.runAsync(() -> {
            try {
                acceptClient();
                // The gateway's reader starts in its constructor, so an event sent before
                // setEventListener() would be dropped. Wait until the listener is in place.
                if (!listenerRegistered.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Event listener was not registered in time");
                }
                // Send unsolicited server event with empty requestId
                JsonObject eventPayload = new JsonObject();
                eventPayload.addProperty("roomCode", "RM9999");
                Message eventMessage = Message.of(MessageType.PARTICIPANTS_UPDATED, "", eventPayload);
                sendToClient(eventMessage);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try (TcpHostGateway gateway = new TcpHostGateway("127.0.0.1", port)) {
            gateway.setEventListener(msg -> {
                receivedEvent.set(msg);
                eventLatch.countDown();
            });
            listenerRegistered.countDown();

            serverTask.get(2, TimeUnit.SECONDS);

            boolean arrived = eventLatch.await(3, TimeUnit.SECONDS);
            assertTrue(arrived, "Unsolicited event should arrive at listener");
            assertNotNull(receivedEvent.get());
            assertEquals(MessageType.PARTICIPANTS_UPDATED, receivedEvent.get().type());
            assertEquals("", receivedEvent.get().requestId());
            assertEquals("RM9999", receivedEvent.get().payload().get("roomCode").getAsString());
        }
    }

    @Test
    void garbageLinesDoNotStopLaterValidReplies() throws Exception {
        CompletableFuture<Void> serverTask = CompletableFuture.runAsync(() -> {
            try {
                acceptClient();
                // Send raw garbage lines
                sendRawToClient("THIS_IS_CORRUPT_RAW_GARBAGE");
                sendRawToClient("{\"broken\": json unclosed");

                // Now read the client's request
                String line = serverIn.readLine();
                Message request = CODEC.decode(line);

                // Send valid reply
                JsonObject replyPayload = new JsonObject();
                replyPayload.addProperty("roomCode", "VALID_ROOM");
                Message reply = Message.of(MessageType.ROOM_CREATED, request.requestId(), replyPayload);
                sendToClient(reply);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try (TcpHostGateway gateway = new TcpHostGateway("127.0.0.1", port)) {
            CompletableFuture<String> future = gateway.createRoom(sampleQuiz());
            String roomCode = future.get(3, TimeUnit.SECONDS);
            assertEquals("VALID_ROOM", roomCode);
            serverTask.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void serverDisconnectFailsPendingRequestsAndTriggersListenerOnce() throws Exception {
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        AtomicInteger disconnectCount = new AtomicInteger(0);

        CompletableFuture<Void> serverTask = CompletableFuture.runAsync(() -> {
            try {
                acceptClient();
                // Read client request
                serverIn.readLine();
                // Abruptly close connection without replying
                clientSocket.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try (TcpHostGateway gateway = new TcpHostGateway("127.0.0.1", port)) {
            gateway.setDisconnectListener(() -> {
                disconnectCount.incrementAndGet();
                disconnectLatch.countDown();
            });

            CompletableFuture<Void> startFuture = gateway.startQuiz("ROOM_DISCONNECT");
            ExecutionException exception = assertThrows(ExecutionException.class, () -> startFuture.get(3, TimeUnit.SECONDS));

            assertInstanceOf(HostException.class, exception.getCause());
            assertEquals("DISCONNECTED", ((HostException) exception.getCause()).getCode());

            boolean disconnectFired = disconnectLatch.await(3, TimeUnit.SECONDS);
            assertTrue(disconnectFired, "Disconnect listener should be called");
            assertEquals(1, disconnectCount.get(), "Disconnect listener must be called exactly once");
            assertTrue(gateway.isClosed());
            serverTask.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void connectingToClosedPortFailsCleanly() {
        int closedPort = 50949;
        assertThrows(IOException.class, () -> new TcpHostGateway("127.0.0.1", closedPort, 1000, 500));
    }
}
