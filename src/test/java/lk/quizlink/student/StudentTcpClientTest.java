package lk.quizlink.student;

import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageCodec;
import lk.quizlink.common.protocol.MessageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudentTcpClientTest {
    @Test
    void connectsSendsAndReceivesOneProtocolMessage() throws Exception {
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        MessageCodec codec = new MessageCodec();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                             socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    Message received = codec.decode(reader.readLine());
                    assertEquals(MessageType.PING, received.type());
                    assertEquals("request-1", received.requestId());

                    writer.write(codec.encode(Message.of(MessageType.PONG, "request-1", new JsonObject())));
                    writer.flush();
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });
            serverThread.start();

            try (StudentTcpClient client = StudentTcpClient.connect("127.0.0.1", server.getLocalPort())) {
                client.send(Message.of(MessageType.PING, "request-1", new JsonObject()));
                Message response = client.receive();
                assertEquals(MessageType.PONG, response.type());
                assertEquals("request-1", response.requestId());
            }

            serverThread.join(3_000);
            assertTrue(!serverThread.isAlive(), "test server did not finish");
            if (serverFailure.get() != null) {
                throw new AssertionError("test server failed", serverFailure.get());
            }
        }
    }

    @Test
    void rejectsInvalidConnectionArguments() {
        assertThrows(IllegalArgumentException.class, () -> StudentTcpClient.connect(" ", 5000));
        assertThrows(IllegalArgumentException.class, () -> StudentTcpClient.connect("localhost", 0));
        assertThrows(IllegalArgumentException.class, () -> StudentTcpClient.connect("localhost", 65_536));
    }
}
