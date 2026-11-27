package lk.quizlink.lobby;

import lk.quizlink.common.model.Quiz;

import java.util.Objects;

/**
 * Immutable lobby information for one room.
 * <p>
 * The room holds its canonical code and the shared immutable quiz definition. It intentionally
 * contains no participant membership, quiz progress, timer, score, or leaderboard state.
 *
 * @param roomCode canonical six-character room code
 * @param quiz quiz definition associated with the room
 */
public record Room(String roomCode, Quiz quiz) {

    /**
     * Validates the room code and quiz reference.
     */
    public Room {
        roomCode = RoomCodeGenerator.normalizeCode(roomCode);
        Objects.requireNonNull(quiz, "quiz must not be null");
    }
}
