package lk.quizlink.quiz;

import lk.quizlink.common.protocol.Message;

/**
 * Receives room-scoped events (QUESTION, LEADERBOARD, QUIZ_ENDED) to broadcast to every connection
 * in the room.
 *
 * <p>The engine calls this from its worker and timer threads, never from the Selector thread.
 * Implementations must be thread-safe and non-blocking, and must not call back into the engine.
 * The server should enqueue the message for each connection and call {@code selector.wakeup()}.
 * Events for one room are published in order.
 */
@FunctionalInterface
public interface QuizEventSink {
    void publish(String roomCode, Message event);
}
