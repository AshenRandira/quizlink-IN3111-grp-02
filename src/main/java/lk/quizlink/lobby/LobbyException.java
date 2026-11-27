package lk.quizlink.lobby;

import java.util.Objects;

/**
 * Domain failure raised while creating a room or managing its membership.
 * <p>
 * These reasons are internal categories rather than shared wire-protocol codes. The future
 * network layer is responsible for translating them to the agreed protocol errors.
 */
public final class LobbyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Reason reason;

    public LobbyException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public Reason reason() {
        return reason;
    }

    /** Lobby-level failure categories; these are not new shared wire-protocol codes. */
    public enum Reason {
        INVALID_QUIZ,
        ROOM_CODE_ALLOCATION_FAILED,
        INVALID_ROOM,
        INVALID_DISPLAY_NAME,
        PARTICIPANT_ID_ALLOCATION_FAILED
    }
}
