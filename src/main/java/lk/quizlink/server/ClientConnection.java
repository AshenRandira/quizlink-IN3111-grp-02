package lk.quizlink.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Everything the server remembers about ONE connected client.
 *
 * Two jobs:
 *  1. Reading: TCP is a byte stream, so a read may contain half a message or three messages.
 *     We keep the bytes that arrived so far (the "inbound buffer") and cut out complete
 *     lines whenever a newline appears.
 *  2. Writing: a non-blocking write may send only part of the data, so outgoing messages wait
 *     in a queue until the channel can accept them.
 */
public final class ClientConnection {

    /** Thrown when a client sends a line that is longer than the allowed frame size. */
    public static final class FrameTooLargeException extends Exception {
        public FrameTooLargeException(String message) {
            super(message);
        }
    }

    private final String id;
    private final SocketChannel channel;
    private final String remoteAddress;

    // Inbound: bytes received but not yet turned into messages.
    private byte[] inbound = new byte[1024];
    private int inboundLength = 0;

    // Outbound: encoded messages waiting to be written (thread-safe, producers may be workers).
    private final Queue<ByteBuffer> outbound = new ConcurrentLinkedQueue<>();

    private SelectionKey key;
    private volatile boolean closeAfterWrite = false;
    private volatile boolean closed = false;

    // Filled in later by the lobby/quiz modules. Never trust what a client *claims*
    // about its room or identity; the server stores what it verified here.
    private volatile String roomCode;
    private volatile String participantId;

    public ClientConnection(String id, SocketChannel channel) {
        this.id = id;
        this.channel = channel;
        this.remoteAddress = describe(channel);
    }

    private static String describe(SocketChannel channel) {
        try {
            return String.valueOf(channel.getRemoteAddress());
        } catch (IOException e) {
            return "unknown";
        }
    }

    // ---------------------------------------------------------------- reading

    /**
     * Add newly received bytes and return every COMPLETE line that is now available.
     * An incomplete last line stays in the buffer until the rest arrives.
     *
     * Splitting on the byte 0x0A is safe even for UTF-8 text: bytes of multi-byte characters
     * are always 0x80 or higher, so a newline byte can never be part of a character.
     * We only turn bytes into a String after a whole line is present, so a character that
     * was split across two reads is rebuilt correctly.
     */
    public List<String> extractFrames(ByteBuffer incoming, int maxFrameBytes) throws FrameTooLargeException {
        append(incoming);

        List<String> frames = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < inboundLength; i++) {
            if (inbound[i] != '\n') {
                continue;
            }
            int end = i;
            if (end > start && inbound[end - 1] == '\r') {
                end--; // accept CRLF as well as LF
            }
            if (end - start > maxFrameBytes) {
                throw new FrameTooLargeException("Message longer than " + maxFrameBytes + " bytes");
            }
            frames.add(new String(inbound, start, end - start, StandardCharsets.UTF_8));
            start = i + 1;
        }

        // Keep the incomplete tail (if any) at the front of the buffer.
        int leftover = inboundLength - start;
        if (leftover > maxFrameBytes) {
            throw new FrameTooLargeException("Message longer than " + maxFrameBytes + " bytes without a newline");
        }
        System.arraycopy(inbound, start, inbound, 0, leftover);
        inboundLength = leftover;
        return frames;
    }

    private void append(ByteBuffer incoming) {
        int count = incoming.remaining();
        int needed = inboundLength + count;
        if (needed > inbound.length) {
            byte[] bigger = new byte[Math.max(needed, inbound.length * 2)];
            System.arraycopy(inbound, 0, bigger, 0, inboundLength);
            inbound = bigger;
        }
        incoming.get(inbound, inboundLength, count);
        inboundLength += count;
    }

    // ---------------------------------------------------------------- writing

    /** Queue bytes for sending. The server enables OP_WRITE so they get written. */
    void queueOutbound(ByteBuffer data) {
        outbound.add(data);
    }

    Queue<ByteBuffer> outboundQueue() {
        return outbound;
    }

    // ---------------------------------------------------------------- state

    public String getId() {
        return id;
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    SelectionKey getKey() {
        return key;
    }

    void setKey(SelectionKey key) {
        this.key = key;
    }

    boolean isCloseAfterWrite() {
        return closeAfterWrite;
    }

    void markCloseAfterWrite() {
        this.closeAfterWrite = true;
    }

    boolean isClosed() {
        return closed;
    }

    void markClosed() {
        this.closed = true;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void setRoomCode(String roomCode) {
        this.roomCode = roomCode;
    }

    public String getParticipantId() {
        return participantId;
    }

    public void setParticipantId(String participantId) {
        this.participantId = participantId;
    }

    @Override
    public String toString() {
        return id + " (" + remoteAddress + ")";
    }
}
