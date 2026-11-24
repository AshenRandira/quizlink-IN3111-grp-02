package lk.quizlink.host;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lk.quizlink.common.model.Quiz;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageType;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * In-memory simulated implementation of {@link HostGateway} for standalone UI testing and demos.
 * <p>
 * Simulates room creation with a ~300ms background delay and emits mock {@code PARTICIPANTS_UPDATED}
 * events. Tracks room lifecycle states (CREATED, STARTED, ENDED) to produce authentic
 * {@link HostException} errors when invalid actions are attempted.
 */
public class MockHostGateway implements HostGateway {

    private static final String ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private enum RoomState {
        CREATED,
        STARTED,
        ENDED
    }

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MockHostGateway-Worker");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<String, RoomState> rooms = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile Consumer<Message> eventListener;
    private volatile Runnable disconnectListener;

    @Override
    public CompletableFuture<String> createRoom(Quiz quiz) {
        if (quiz == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("quiz must not be null"));
        }
        if (closed.get()) {
            return CompletableFuture.failedFuture(new HostException("CLOSED", "Mock gateway is closed"));
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        executor.schedule(() -> {
            String roomCode = generateRoomCode();
            rooms.put(roomCode, RoomState.CREATED);
            future.complete(roomCode);

            // Emit simulated PARTICIPANTS_UPDATED event
            Consumer<Message> listener = this.eventListener;
            if (listener != null) {
                JsonObject payload = new JsonObject();
                payload.addProperty("roomCode", roomCode);

                JsonArray participants = new JsonArray();
                JsonObject mockStudent = new JsonObject();
                mockStudent.addProperty("id", "student-mock-1");
                mockStudent.addProperty("displayName", "Alice (Demo)");
                participants.add(mockStudent);

                payload.add("participants", participants);

                Message event = Message.of(MessageType.PARTICIPANTS_UPDATED, "", payload);
                try {
                    listener.accept(event);
                } catch (Exception ignored) {
                }
            }
        }, 300, TimeUnit.MILLISECONDS);

        return future;
    }

    @Override
    public CompletableFuture<Void> startQuiz(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("roomCode is required and must not be blank"));
        }
        if (closed.get()) {
            return CompletableFuture.failedFuture(new HostException("CLOSED", "Mock gateway is closed"));
        }

        RoomState state = rooms.get(roomCode);
        if (state == null) {
            return CompletableFuture.failedFuture(new HostException("INVALID_ROOM", "Room does not exist: " + roomCode));
        }
        if (state == RoomState.STARTED) {
            return CompletableFuture.failedFuture(new HostException("ALREADY_STARTED", "Quiz has already started in room: " + roomCode));
        }
        if (state == RoomState.ENDED) {
            return CompletableFuture.failedFuture(new HostException("QUIZ_ENDED", "Quiz has already ended in room: " + roomCode));
        }

        rooms.put(roomCode, RoomState.STARTED);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> endQuiz(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("roomCode is required and must not be blank"));
        }
        if (closed.get()) {
            return CompletableFuture.failedFuture(new HostException("CLOSED", "Mock gateway is closed"));
        }

        RoomState state = rooms.get(roomCode);
        if (state == null) {
            return CompletableFuture.failedFuture(new HostException("INVALID_ROOM", "Room does not exist: " + roomCode));
        }
        if (state == RoomState.CREATED) {
            return CompletableFuture.failedFuture(new HostException("QUIZ_NOT_STARTED", "Cannot end quiz that has not started: " + roomCode));
        }
        if (state == RoomState.ENDED) {
            return CompletableFuture.failedFuture(new HostException("ALREADY_ENDED", "Quiz has already ended in room: " + roomCode));
        }

        rooms.put(roomCode, RoomState.ENDED);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void setEventListener(Consumer<Message> listener) {
        this.eventListener = listener;
    }

    @Override
    public void setDisconnectListener(Runnable listener) {
        this.disconnectListener = listener;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
            rooms.clear();
        }
    }

    private String generateRoomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHANUM.charAt(RANDOM.nextInt(ALPHANUM.length())));
        }
        return sb.toString();
    }
}
