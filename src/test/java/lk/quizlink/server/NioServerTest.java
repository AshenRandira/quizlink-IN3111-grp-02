package lk.quizlink.server;

import com.google.gson.JsonObject;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageCodec;
import lk.quizlink.common.protocol.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-socket tests: each test starts the server on a free port and talks to it like a client. */
class NioServerTest {

    private static final int SMALL_FRAME_LIMIT = 1024;
    private static final MessageCodec CODEC = new MessageCodec();

    private MessageRouter router;
    private NioServer server;
    private Thread serverThread;
    private final List<TestClient> clients = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        router = new MessageRouter();
        server = new NioServer(0, router, SMALL_FRAME_LIMIT); // port 0 = any free port
        server.open();
        serverThread = new Thread(server::run, "test-selector-thread");
        serverThread.start();
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        for (TestClient client : clients) {
            client.closeQuietly();
        }
        server.stop();
        serverThread.join(3000);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void pingGetsPongWithSameRequestId() throws IOException {
        TestClient client = connect();
        client.send(ping("req-1"));

        Message reply = client.readMessage();
        assertEquals(MessageType.PONG, reply.type());
        assertEquals("req-1", reply.requestId());
    }

    @Test
    void messageSplitAcrossTwoWritesIsReassembled() throws Exception {
        TestClient client = connect();
        // "é" is two bytes in UTF-8. Cut right between them to test the nastiest split.
        byte[] frame = CODEC.encode(ping("réq-é")).getBytes(StandardCharsets.UTF_8);
        int cut = indexOfByte(frame, (byte) 0xC3) + 1;

        client.sendBytes(slice(frame, 0, cut));
        Thread.sleep(150); // let the server read the first half and wait for the rest
        client.sendBytes(slice(frame, cut, frame.length));

        Message reply = client.readMessage();
        assertEquals(MessageType.PONG, reply.type());
        assertEquals("réq-é", reply.requestId());
    }

    @Test
    void twoMessagesInOneWriteAreBothAnswered() throws IOException {
        TestClient client = connect();
        client.sendRaw(CODEC.encode(ping("a")) + CODEC.encode(ping("b")));

        assertEquals("a", client.readMessage().requestId());
        assertEquals("b", client.readMessage().requestId());
    }

    @Test
    void malformedMessageGetsErrorAndConnectionStaysOpen() throws IOException {
        TestClient client = connect();
        client.sendRaw("this is not json\n");

        Message error = client.readMessage();
        assertEquals(MessageType.ERROR, error.type());
        assertEquals("INVALID_MESSAGE", error.payload().get("code").getAsString());

        // The same connection still works.
        client.send(ping("after-error"));
        assertEquals(MessageType.PONG, client.readMessage().type());
    }

    @Test
    void oversizedFrameGetsErrorThenConnectionIsClosed() throws IOException {
        TestClient client = connect();
        client.sendRaw("x".repeat(SMALL_FRAME_LIMIT * 5)); // no newline, far over the limit

        Message error = client.readMessage();
        assertEquals(MessageType.ERROR, error.type());
        assertNull(client.readLine(), "server should close the connection after the error");
    }

    @Test
    void manyClientsCanBeConnectedAtTheSameTime() throws IOException {
        List<TestClient> group = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            group.add(connect());
        }
        awaitTrue(() -> server.getConnectionCount() == 10);

        for (int i = 0; i < group.size(); i++) {
            group.get(i).send(ping("client-" + i));
        }
        for (int i = 0; i < group.size(); i++) {
            Message reply = group.get(i).readMessage();
            assertEquals(MessageType.PONG, reply.type());
            assertEquals("client-" + i, reply.requestId());
        }
    }

    @Test
    void abruptDisconnectDoesNotAffectOtherClients() throws Exception {
        AtomicInteger disconnects = new AtomicInteger();
        router.onDisconnect(connection -> disconnects.incrementAndGet());

        TestClient stayer = connect();
        TestClient dropper = connect();
        awaitTrue(() -> server.getConnectionCount() == 2);

        dropper.resetConnection(); // sends a TCP RST, like a crashed client / pulled cable
        awaitTrue(() -> server.getConnectionCount() == 1);
        awaitTrue(() -> disconnects.get() == 1);

        stayer.send(ping("still-alive"));
        assertEquals("still-alive", stayer.readMessage().requestId());
    }

    @Test
    void messageTypeWithoutHandlerGetsMockError() throws IOException {
        TestClient client = connect();
        client.send(Message.of(MessageType.JOIN_ROOM, "join-1", new JsonObject()));

        Message reply = client.readMessage();
        assertEquals(MessageType.ERROR, reply.type());
        assertEquals("join-1", reply.requestId());
        assertEquals("INTERNAL_ERROR", reply.payload().get("code").getAsString());
    }

    @Test
    void clientCannotSendServerOnlyMessageTypes() throws IOException {
        TestClient client = connect();
        client.send(Message.of(MessageType.PONG, "x", new JsonObject()));

        Message reply = client.readMessage();
        assertEquals(MessageType.ERROR, reply.type());
        assertEquals("INVALID_MESSAGE", reply.payload().get("code").getAsString());
    }

    @Test
    void otherModulesCanRegisterAHandler() throws IOException {
        JsonObject joined = new JsonObject();
        joined.addProperty("roomCode", "AB12CD");
        router.register(MessageType.JOIN_ROOM, (connection, message, sender) ->
                sender.send(connection, Message.of(MessageType.ROOM_JOINED, message.requestId(), joined)));

        TestClient client = connect();
        client.send(Message.of(MessageType.JOIN_ROOM, "join-2", new JsonObject()));

        Message reply = client.readMessage();
        assertEquals(MessageType.ROOM_JOINED, reply.type());
        assertEquals("join-2", reply.requestId());
    }

    @Test
    void failingHandlerDoesNotCrashServer() throws IOException {
        router.register(MessageType.JOIN_ROOM, (connection, message, sender) -> {
            throw new IllegalStateException("boom");
        });

        TestClient client = connect();
        client.send(Message.of(MessageType.JOIN_ROOM, "bad", new JsonObject()));
        Message error = client.readMessage();
        assertEquals(MessageType.ERROR, error.type());
        assertTrue(!error.payload().get("message").getAsString().contains("boom"),
                "internal details must not be sent to clients");

        client.send(ping("next"));
        assertEquals(MessageType.PONG, client.readMessage().type());
    }

    // ------------------------------------------------------------------ helpers

    private static Message ping(String requestId) {
        return Message.of(MessageType.PING, requestId, new JsonObject());
    }

    private TestClient connect() throws IOException {
        TestClient client = new TestClient(server.getPort());
        clients.add(client);
        return client;
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), "condition not met within 3 seconds");
    }

    private static int indexOfByte(byte[] data, byte value) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        throw new IllegalArgumentException("byte not found");
    }

    private static byte[] slice(byte[] data, int from, int to) {
        byte[] part = new byte[to - from];
        System.arraycopy(data, from, part, 0, part.length);
        return part;
    }

    /** A plain blocking-socket client, like the student application uses. */
    private static final class TestClient {
        private final Socket socket;
        private final BufferedReader reader;
        private final OutputStream out;

        TestClient(int port) throws IOException {
            socket = new Socket(InetAddress.getLoopbackAddress(), port);
            socket.setSoTimeout(3000); // a missing reply fails the test instead of hanging
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
        }

        void send(Message message) throws IOException {
            sendRaw(CODEC.encode(message));
        }

        void sendRaw(String text) throws IOException {
            sendBytes(text.getBytes(StandardCharsets.UTF_8));
        }

        void sendBytes(byte[] bytes) throws IOException {
            out.write(bytes);
            out.flush();
        }

        String readLine() throws IOException {
            return reader.readLine();
        }

        Message readMessage() throws IOException {
            String line = readLine();
            if (line == null) {
                throw new IOException("connection closed before a reply arrived");
            }
            return CODEC.decode(line);
        }

        void resetConnection() throws IOException {
            socket.setSoLinger(true, 0); // close() now sends RST instead of a normal FIN
            socket.close();
        }

        void closeQuietly() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // test cleanup
            }
        }
    }
}
