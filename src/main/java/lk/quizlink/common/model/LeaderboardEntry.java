package lk.quizlink.common.model;

public record LeaderboardEntry(String participantId, String displayName, int score, int rank) {
    public LeaderboardEntry {
        participantId = ModelValidation.requiredText(participantId, "participantId");
        displayName = ModelValidation.displayName(displayName);
        if (score < 0) {
            throw new IllegalArgumentException("score must not be negative");
        }
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be at least 1");
        }
    }
}
