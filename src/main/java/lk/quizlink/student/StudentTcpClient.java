package lk.quizlink.student;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageCodec;

/**
 * Blocking TCP client used by the student application.
 *
 * <p>Call this class from a background thread so socket operations do not block
 * Swing's Event Dispatch Thread. The server owns message routing and authority;
 * this class only handles the connection and one-message-at-a-time framing.</p>
 */
public final class StudentTcpClient implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    private final Socket socket;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final MessageCodec codec;

    private StudentTcpClient(Socket socket) throws IOException {
        this.socket = socket;
        this.reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8));
        this.codec = new MessageCodec();
    }

    /** Connects to a QuizLink server using the configured timeout. */
    public static StudentTcpClient connect(String host, int port) throws IOException {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host is required");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host.trim(), port), CONNECT_TIMEOUT_MILLIS);
            return new StudentTcpClient(socket);
        } catch (IOException | RuntimeException exception) {
            try {
                socket.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /** Sends one complete protocol message and flushes it to the server. */
    public synchronized void send(Message message) throws IOException {
        writer.write(codec.encode(message));
        writer.flush();
    }

    /** Reads and decodes one complete protocol message from the server. */
    public Message receive() throws IOException {
        String line = reader.readLine();
        if (line == null) {
            throw new EOFException("Server closed the connection");
        }
        try {
            return codec.decode(line);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Server sent an invalid protocol message", exception);
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
