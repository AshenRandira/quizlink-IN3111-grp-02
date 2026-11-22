package lk.quizlink.student;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import lk.quizlink.common.model.LeaderboardEntry;
import lk.quizlink.common.model.QuestionView;
import lk.quizlink.common.protocol.Message;
import lk.quizlink.common.protocol.MessageType;
import lk.quizlink.common.protocol.ProtocolConstants;

/** Coordinates background network operations with the student Swing view. */
public final class StudentClientController implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final Type LEADERBOARD_TYPE =
            new TypeToken<List<LeaderboardEntry>>() { }.getType();

    private final StudentClientView view;
    private final int serverPort;
    private final Consumer<Runnable> uiDispatcher;
    private final ExecutorService networkExecutor;
    private volatile StudentTcpClient client;
    private volatile boolean closed;

    public StudentClientController(StudentClientView view) {
        this(view, ProtocolConstants.TCP_PORT, SwingUtilities::invokeLater);
    }

    StudentClientController(StudentClientView view, int serverPort,
                            Consumer<Runnable> uiDispatcher) {
        if (view == null) {
            throw new IllegalArgumentException("view is required");
        }
        this.view = view;
        this.serverPort = serverPort;
        this.uiDispatcher = uiDispatcher;
        this.networkExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "quizlink-student-network");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Connects and sends JOIN_ROOM without blocking Swing's Event Dispatch Thread. */
    public void connectAndJoin(String serverHost, StudentJoinRequest request) {
        dispatch(view::showConnecting);
        networkExecutor.submit(() -> performJoin(serverHost, request));
    }

    private void performJoin(String serverHost, StudentJoinRequest request) {
        try {
            closeCurrentClient();
            StudentTcpClient connectedClient = StudentTcpClient.connect(serverHost, serverPort);
            client = connectedClient;

            String requestId = UUID.randomUUID().toString();
            JsonObject payload = new JsonObject();
            payload.addProperty("roomCode", request.roomCode());
            payload.addProperty("displayName", request.displayName());
            connectedClient.send(Message.of(MessageType.JOIN_ROOM, requestId, payload));

            Message response = connectedClient.receive();
            handleJoinResponse(response, requestId, request);
            receiveServerEvents(connectedClient);
        } catch (IOException | RuntimeException exception) {
            closeCurrentClientQuietly();
            if (!closed) {
                dispatch(() -> view.showConnectionError(readableMessage(exception)));
            }
        }
    }

    private void handleJoinResponse(Message response, String requestId,
                                    StudentJoinRequest request) throws IOException {
        if (!requestId.equals(response.requestId())) {
            throw new IOException("Server reply did not match the join request");
        }
        if (response.type() == MessageType.ROOM_JOINED) {
            dispatch(() -> view.showLobbyScreen(request));
            return;
        }
        if (response.type() == MessageType.ERROR) {
            throw new IOException(errorMessage(response.payload()));
        }
        throw new IOException("Unexpected server reply: " + response.type());
    }

    private void receiveServerEvents(StudentTcpClient connectedClient) throws IOException {
        while (!closed) {
            Message message = connectedClient.receive();
            if (!handleServerEvent(message)) {
                return;
            }
        }
    }

    private boolean handleServerEvent(Message message) throws IOException {
        try {
            return switch (message.type()) {
                case QUESTION -> {
                    JsonObject payload = message.payload();
                    QuestionView question = GSON.fromJson(
                            requiredObject(payload, "question"), QuestionView.class);
                    int questionNumber = requiredInt(payload, "questionNumber");
                    int totalQuestions = requiredInt(payload, "totalQuestions");
                    dispatch(() -> view.showQuestion(question, questionNumber, totalQuestions));
                    yield true;
                }
                case LEADERBOARD, QUIZ_ENDED -> {
                    List<LeaderboardEntry> entries = List.copyOf(GSON.fromJson(
                            message.payload().getAsJsonArray("entries"), LEADERBOARD_TYPE));
                    boolean finalResults = message.type() == MessageType.QUIZ_ENDED;
                    dispatch(() -> view.showLeaderboard(entries, finalResults));
                    yield !finalResults;
                }
                case PARTICIPANTS_UPDATED, ANSWER_RESULT, PONG -> true;
                case ERROR -> throw new IOException(errorMessage(message.payload()));
                default -> throw new IOException("Unexpected server message: " + message.type());
            };
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException exception) {
            throw new IOException("Invalid payload for server message " + message.type(), exception);
        }
    }

    private static JsonObject requiredObject(JsonObject payload, String fieldName) {
        JsonElement element = payload.get(fieldName);
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(fieldName + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static int requiredInt(JsonObject payload, String fieldName) {
        JsonElement element = payload.get(fieldName);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(fieldName + " must be a number");
        }
        return element.getAsInt();
    }

    private static String errorMessage(JsonObject payload) {
        JsonElement message = payload.get("message");
        if (message != null && message.isJsonPrimitive()
                && message.getAsJsonPrimitive().isString()) {
            return message.getAsString();
        }
        return "The server rejected the join request";
    }

    private static String readableMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "Unable to connect to the QuizLink server" : message;
    }

    private void dispatch(Runnable action) {
        uiDispatcher.accept(action);
    }

    private void closeCurrentClient() throws IOException {
        StudentTcpClient current = client;
        client = null;
        if (current != null) {
            current.close();
        }
    }

    private void closeCurrentClientQuietly() {
        try {
            closeCurrentClient();
        } catch (IOException ignored) {
            // The original connection error is more useful to the student.
        }
    }

    @Override
    public void close() {
        closed = true;
        closeCurrentClientQuietly();
        networkExecutor.shutdownNow();
    }
}
