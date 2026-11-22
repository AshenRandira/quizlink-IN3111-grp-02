package lk.quizlink.student;

import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageCodec;
import lk.quizlink.common.protocol.MessageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudentClientControllerTest {
    @Test
    void sendsJoinRequestAndShowsLobbyAfterMatchingReply() throws Exception {
        TestView view = new TestView();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        MessageCodec codec = new MessageCodec();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                             socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    Message join = codec.decode(reader.readLine());
                    assertEquals(MessageType.JOIN_ROOM, join.type());
                    assertEquals("AB12CD", join.payload().get("roomCode").getAsString());
                    assertEquals("Student 1", join.payload().get("displayName").getAsString());

                    JsonObject payload = new JsonObject();
                    payload.addProperty("roomCode", "AB12CD");
                    writer.write(codec.encode(Message.of(MessageType.ROOM_JOINED,
                            join.requestId(), payload)));
                    writer.flush();
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });
            serverThread.start();

            try (StudentClientController controller = new StudentClientController(
                    view, server.getLocalPort(), Runnable::run)) {
                controller.connectAndJoin("127.0.0.1",
                        new StudentJoinRequest("AB12CD", "Student 1"));
                assertTrue(view.completed.await(3, TimeUnit.SECONDS));
                assertEquals("AB12CD", view.joinedRequest.roomCode());
                assertNull(view.errorMessage);
            }

            serverThread.join(3_000);
            assertTrue(!serverThread.isAlive(), "test server did not finish");
            if (serverFailure.get() != null) {
                throw new AssertionError("test server failed", serverFailure.get());
            }
        }
    }

    @Test
    void showsServerErrorWhenJoinIsRejected() throws Exception {
        TestView view = new TestView();
        MessageCodec codec = new MessageCodec();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                             socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    Message join = codec.decode(reader.readLine());
                    JsonObject payload = new JsonObject();
                    payload.addProperty("code", "INVALID_ROOM");
                    payload.addProperty("message", "Room does not exist");
                    writer.write(codec.encode(Message.of(MessageType.ERROR,
                            join.requestId(), payload)));
                    writer.flush();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            serverThread.start();

            try (StudentClientController controller = new StudentClientController(
                    view, server.getLocalPort(), Runnable::run)) {
                controller.connectAndJoin("127.0.0.1",
                        new StudentJoinRequest("BAD123", "Student 1"));
                assertTrue(view.completed.await(3, TimeUnit.SECONDS));
                assertEquals("Room does not exist", view.errorMessage);
            }

            serverThread.join(3_000);
            assertTrue(!serverThread.isAlive(), "test server did not finish");
        }
    }

    private static final class TestView implements StudentClientView {
        private final CountDownLatch completed = new CountDownLatch(1);
        private StudentJoinRequest joinedRequest;
        private String errorMessage;

        @Override
        public void showConnecting() {
        }

        @Override
        public void showLobbyScreen(StudentJoinRequest request) {
            joinedRequest = request;
            completed.countDown();
        }

        @Override
        public void showConnectionError(String message) {
            errorMessage = message;
            completed.countDown();
        }
    }
}
