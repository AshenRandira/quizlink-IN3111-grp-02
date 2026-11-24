package lk.quizlink.host;

import lk.quizlink.common.model.Quiz;
import lk.quizlink.common.protocol.Message;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Primary networking contract for the QuizLink host application.
 * All UI controllers and dashboards interact exclusively through this interface.
 */
public interface HostGateway extends AutoCloseable {

    /**
     * Sends a request to create a new quiz room on the server.
     *
     * @param quiz the full quiz definition
     * @return a future completing with the assigned room code
     */
    CompletableFuture<String> createRoom(Quiz quiz);

    /**
     * Commands the server to start the quiz for the specified room.
     *
     * @param roomCode the room identifier
     * @return a future completing when the server confirms start
     */
    CompletableFuture<Void> startQuiz(String roomCode);

    /**
     * Commands the server to end the quiz for the specified room.
     *
     * @param roomCode the room identifier
     * @return a future completing when the server confirms end
     */
    CompletableFuture<Void> endQuiz(String roomCode);

    /**
     * Registers a listener to receive unsolicited server events (e.g. PARTICIPANTS_UPDATED, LEADERBOARD).
     *
     * @param listener consumer receiving incoming event messages
     */
    void setEventListener(Consumer<Message> listener);

    /**
     * Registers a listener invoked once if the connection to the server drops.
     *
     * @param listener runnable executed upon disconnection
     */
    void setDisconnectListener(Runnable listener);

    /**
     * Closes the connection and releases any underlying network resources.
     */
    @Override
    void close();
}
