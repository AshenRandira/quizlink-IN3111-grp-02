package lk.quizlink.student;

/** Validated values entered by a student before a server join request is sent. */
public record StudentJoinRequest(String roomCode, String displayName) {
    public StudentJoinRequest {
        roomCode = required(roomCode, "roomCode").toUpperCase();
        displayName = required(displayName, "displayName");
    }

    private static String required(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
