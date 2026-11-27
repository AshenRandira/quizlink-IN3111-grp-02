package lk.quizlink.lobby;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/**
 * Generates six-character room-code candidates using uppercase letters and digits.
 * <p>
 * Candidates are identifiers, not credentials. The lobby remains responsible for ensuring
 * that a candidate is not already assigned to an active room.
 */
public final class RoomCodeGenerator {

    public static final int CODE_LENGTH = 6;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String CODE_PATTERN = "[A-Z0-9]{6}";

    private final IntUnaryOperator randomIndex;

    /**
     * Creates a generator backed by a cryptographically strong random source.
     */
    public RoomCodeGenerator() {
        SecureRandom random = new SecureRandom();
        this.randomIndex = random::nextInt;
    }

    RoomCodeGenerator(IntUnaryOperator randomIndex) {
        this.randomIndex = Objects.requireNonNull(randomIndex, "randomIndex must not be null");
    }

    /**
     * Generates one code candidate. Uniqueness is enforced by {@link LobbyService}.
     *
     * @return six uppercase alphanumeric characters
     */
    public synchronized String generateCandidate() {
        StringBuilder candidate = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            int index = randomIndex.applyAsInt(ALPHABET.length());
            if (index < 0 || index >= ALPHABET.length()) {
                throw new IllegalStateException("Random source returned an invalid alphabet index");
            }
            candidate.append(ALPHABET.charAt(index));
        }
        return candidate.toString();
    }

    /**
     * Returns whether a code is syntactically valid after trimming and case normalization.
     * This does not indicate whether an active room has that code.
     *
     * @param roomCode code supplied by a caller
     * @return true for a normalized six-character uppercase alphanumeric code
     */
    public static boolean isValidCode(String roomCode) {
        if (roomCode == null) {
            return false;
        }
        try {
            normalizeCode(roomCode);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * Trims and uppercases a syntactically valid room code using {@link Locale#ROOT}.
     *
     * @param roomCode input code
     * @return canonical uppercase code
     * @throws IllegalArgumentException if the input is null, blank, or malformed
     */
    public static String normalizeCode(String roomCode) {
        if (roomCode == null) {
            throw new IllegalArgumentException("roomCode is required");
        }
        String normalized = roomCode.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches(CODE_PATTERN)) {
            throw new IllegalArgumentException("roomCode must contain six letters or digits");
        }
        return normalized;
    }
}
