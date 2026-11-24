package lk.quizlink.multicast;

/**
 * Immutable announcement payload broadcast over multicast UDP.
 * <p>
 * Room announcements are best-effort informational messages and must never
 * carry authoritative quiz state, questions, student answers, or scores.
 *
 * @param roomCode the target room identifier
 * @param text the announcement text to display (up to 500 characters, no control characters)
 */
public record Announcement(String roomCode, String text) {

    public static final int MAX_TEXT_LENGTH = 500;

    public Announcement {
        if (roomCode == null || roomCode.isBlank()) {
            throw new IllegalArgumentException("roomCode is required and must not be blank");
        }
        if (roomCode.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("roomCode must not contain control characters");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required and must not be blank");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("text length exceeds maximum limit of " + MAX_TEXT_LENGTH + " characters");
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("text must not contain control characters");
        }
    }
}
